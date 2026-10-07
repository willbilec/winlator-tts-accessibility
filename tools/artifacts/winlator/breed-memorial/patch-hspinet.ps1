param([string]$Source = "$PSScriptRoot/device/hspinet.dll", [string]$Destination = "$PSScriptRoot/device/hspinet-guard.dll")
$bytes = [IO.File]::ReadAllBytes($Source)
$pe = [BitConverter]::ToInt32($bytes, 0x3c)
$sections = [BitConverter]::ToUInt16($bytes, $pe+6)
$optional = $pe+24
$sectionTable = $optional+[BitConverter]::ToUInt16($bytes, $pe+20)
$fileAlign = [BitConverter]::ToUInt32($bytes, $optional+36)
$sectionAlign = [BitConverter]::ToUInt32($bytes, $optional+32)
function Align([int]$Value, [int]$Alignment) { return [int]([Math]::Ceiling($Value/[double]$Alignment)*$Alignment) }
function Put32([byte[]]$Target, [int]$Offset, [uint32]$Value) { [BitConverter]::GetBytes($Value).CopyTo($Target,$Offset) }
function FileOffset([int]$Rva) {
    for ($i=0; $i -lt $sections; $i++) {
        $s = $sectionTable+$i*40
        $va = [BitConverter]::ToUInt32($bytes,$s+12)
        $size = [BitConverter]::ToUInt32($bytes,$s+16)
        if ($Rva -ge $va -and $Rva -lt $va+$size) { return [int]([BitConverter]::ToUInt32($bytes,$s+20)+$Rva-$va) }
    }
    throw "RVA is not backed by a section"
}
$callOffset = FileOffset 0x3829
$expected = [byte[]](0xff,0x15,0x28,0xd1,0x02,0x10)
for ($i=0; $i -lt 6; $i++) { if ($bytes[$callOffset+$i] -ne $expected[$i]) { throw 'Unexpected HttpQueryInfoA call: refusing patch' } }
$newHeader = $sectionTable+$sections*40
$headerLimit = [BitConverter]::ToUInt32($bytes,$optional+60)
if ($newHeader+40 -gt $headerLimit) { throw 'No room for section header' }
for ($i=0; $i -lt 40; $i++) { if ($bytes[$newHeader+$i] -ne 0) { throw 'Section header slot is occupied' } }
$rva = [BitConverter]::ToUInt32($bytes,$optional+56)
$raw = Align $bytes.Length $fileAlign
$result = New-Object byte[] ($raw+$fileAlign)
$bytes.CopyTo($result,0)
# Reject a null output-length pointer; valid calls retain the original imported API.
# The tail jump uses call/pop addressing, so no new PE relocations are needed.
$code = [byte[]](0x83,0x7c,0x24,0x10,0x00,0x75,0x05,0x31,0xc0,0xc2,0x14,0x00,0xe8,0,0,0,0,0x58,0x05,0,0,0,0,0xff,0x20)
[BitConverter]::GetBytes([int](0x2d128-($rva+17))).CopyTo($code,19)
$code.CopyTo($result,$raw)
[Text.Encoding]::ASCII.GetBytes('.bmguard').CopyTo($result,$newHeader)
Put32 $result ($newHeader+8) $code.Length
Put32 $result ($newHeader+12) $rva
Put32 $result ($newHeader+16) $fileAlign
Put32 $result ($newHeader+20) $raw
Put32 $result ($newHeader+36) 0x60000020
[BitConverter]::GetBytes([uint16]($sections+1)).CopyTo($result,$pe+6)
Put32 $result ($optional+56) (Align ($rva+$code.Length) $sectionAlign)
Put32 $result ($optional+64) 0
$result[$callOffset] = 0xe8
[BitConverter]::GetBytes([int]($rva-(0x3829+5))).CopyTo($result,$callOffset+1)
$result[$callOffset+5] = 0x90
# The replaced absolute IAT operand had a HIGHLOW relocation at RVA 0x382b.
# Remove that relocation: a relative call must not be adjusted by the loader.
$relocRva = [BitConverter]::ToUInt32($bytes,$optional+96+5*8)
$relocSize = [BitConverter]::ToUInt32($bytes,$optional+100+5*8)
$relocOffset = FileOffset $relocRva
$cursor = $relocOffset
$removed = 0
while ($cursor -lt $relocOffset+$relocSize) {
    $page = [BitConverter]::ToUInt32($bytes,$cursor)
    $blockSize = [BitConverter]::ToUInt32($bytes,$cursor+4)
    if ($blockSize -lt 8) { throw 'Invalid relocation block' }
    for ($entry=$cursor+8; $entry -lt $cursor+$blockSize; $entry+=2) {
        $item = [BitConverter]::ToUInt16($bytes,$entry)
        if (($item -shr 12) -eq 3 -and $page+($item -band 0xfff) -eq 0x382b) {
            $result[$entry]=0; $result[$entry+1]=0; $removed++
        }
    }
    $cursor += $blockSize
}
if ($removed -ne 1) { throw 'Expected exactly one obsolete IAT relocation' }
[IO.File]::WriteAllBytes($Destination,$result)
Write-Output "Created $Destination; guards only the optional HTTP header query at RVA 0x3829"
