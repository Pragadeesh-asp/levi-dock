# LEVI DOCK wake-word listener (started by levi-host).
# Windows' built-in offline recognizer (System.Speech, en-US) only listens for
# "hey levi". It reports where in the audio stream the wake phrase ended
# ("start", ms from stream start); levi-host then captures what follows and
# transcribes it. Output: JSON lines {"kind":"ready"|"wake","start":ms,"conf":c}.

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Speech
$engine = New-Object System.Speech.Recognition.SpeechRecognitionEngine (New-Object System.Globalization.CultureInfo 'en-US')
if ($env:LEVI_VOICE_PORT) {
    # Audio relayed by levi-host (dock microphone, 16 kHz mono PCM16).
    $client = New-Object System.Net.Sockets.TcpClient('127.0.0.1', [int]$env:LEVI_VOICE_PORT)
    $net = $client.GetStream()
    $hello = [Text.Encoding]::ASCII.GetBytes($env:LEVI_TOKEN + [char]10)
    $net.Write($hello, 0, $hello.Length)
    # SAPI requires a seekable stream; wrap the live socket in one that blocks for audio.
    Add-Type -TypeDefinition @'
public class LeviLiveStream : System.IO.Stream {
    private readonly System.IO.Stream inner; private long pos;
    public LeviLiveStream(System.IO.Stream s) { inner = s; }
    public override bool CanRead { get { return true; } }
    public override bool CanSeek { get { return true; } }
    public override bool CanWrite { get { return false; } }
    public override long Length { get { return long.MaxValue; } }
    public override long Position { get { return pos; } set { } }
    public override void Flush() { }
    public override long Seek(long offset, System.IO.SeekOrigin origin) { return pos; }
    public override void SetLength(long value) { }
    public override void Write(byte[] b, int o, int c) { throw new System.NotSupportedException(); }
    public override int Read(byte[] b, int o, int c) {
        int total = 0;
        while (total < c) { int n = inner.Read(b, o + total, c - total); if (n <= 0) break; total += n; }
        pos += total;
        return total;
    }
}
'@
    $fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    $engine.SetInputToAudioStream((New-Object LeviLiveStream $net), $fmt)
} else {
    $engine.SetInputToDefaultAudioDevice()
}

# "hey levi" alone, and "hey levi" followed by anything (one breath).
$gbWake = New-Object System.Speech.Recognition.GrammarBuilder 'hey levi'
$gWake = New-Object System.Speech.Recognition.Grammar $gbWake
$gWake.Name = 'wake'
$engine.LoadGrammar($gWake)

$gbMore = New-Object System.Speech.Recognition.GrammarBuilder 'hey levi'
$gbMore.AppendDictation()
$gMore = New-Object System.Speech.Recognition.Grammar $gbMore
$gMore.Name = 'wake+'
$engine.LoadGrammar($gMore)

function Emit($kind, $after, $conf) {
    $line = @{ kind = $kind; after = [int64]$after; conf = [math]::Round($conf, 2) } | ConvertTo-Json -Compress
    [Console]::Out.WriteLine($line)
    [Console]::Out.Flush()
}

Emit 'ready' 0 1
while ($true) {
    $r = $engine.Recognize([TimeSpan]::FromSeconds(4))
    if (-not $r -or $r.Confidence -lt 0.5) { continue }
    # How much of the recognised phrase came after "levi" (ms). AudioPosition is not
    # stream-absolute across Recognize() calls, so only offsets within the phrase are used.
    $after = 0
    try {
        if ($r.Words.Count -ge 2) {
            $utt = $r.Audio
            $w = $r.GetAudioForWordRange($r.Words[0], $r.Words[1])
            $leviEnd = ($w.AudioPosition - $utt.AudioPosition).TotalMilliseconds + $w.Duration.TotalMilliseconds
            $after = [math]::Max(0, $utt.Duration.TotalMilliseconds - $leviEnd)
        }
    } catch { }
    Emit 'wake' $after $r.Confidence
}
