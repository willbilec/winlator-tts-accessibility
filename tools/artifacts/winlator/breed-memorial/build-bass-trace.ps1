param([switch]$KeepAlive)
$wrapped = @('BASS_Init','BASS_StreamCreateFile','BASS_SampleLoad','BASS_ChannelPlay','BASS_ChannelStop','BASS_ChannelSetAttribute','BASS_SetConfig','BASS_Start','BASS_Stop')
if ($KeepAlive) { $wrapped = @('BASS_Init') }
$dump = wsl --exec objdump -p ((wsl --exec wslpath -a "$PSScriptRoot/device/bass.dll").Trim())
$exports = @('LIBRARY bass','EXPORTS')
foreach ($line in $dump) {
    if ($line -match '\]\s+\+base\[\s*(\d+)\]\s+[0-9a-f]{4}\s+(\S+)') {
        $ordinal=$Matches[1]; $name=$Matches[2]
        if ($wrapped -contains $name) { $exports += "$name @$ordinal" }
        else { $exports += "$name=bass-original.$name @$ordinal" }
    }
}
if ($exports.Count -lt 50) { throw 'Could not parse the original BASS exports' }
[IO.File]::WriteAllLines("$PSScriptRoot/bass-trace.def",$exports)
$src = (wsl --exec wslpath -a "$PSScriptRoot/bass-trace.c").Trim()
$def = (wsl --exec wslpath -a "$PSScriptRoot/bass-trace.def").Trim()
$outputName = if ($KeepAlive) { 'bass-keepalive.dll' } else { 'bass-trace.dll' }
$out = (wsl --exec wslpath -a "$PSScriptRoot/device/$outputName").Trim()
[string[]]$defines = if ($KeepAlive) { @('-DBM_KEEPALIVE') } else { @() }
wsl --exec i686-w64-mingw32-gcc -shared -O2 -static-libgcc '-Wl,--kill-at' @defines $src $def -o $out
if ($LASTEXITCODE -ne 0) { throw 'BASS trace compilation failed' }
