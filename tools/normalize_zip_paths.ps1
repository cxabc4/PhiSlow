function Normalize-ZipPaths {
    param([string]$Path)
    # Windows aapt2 writes backslashes in nested asset names. Change the names
    # in both headers, preserving the compressed payload and filename lengths.
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite)
    try {
        $reader = [System.IO.BinaryReader]::new($stream)
        $tailSize = [int][Math]::Min(65557, $stream.Length)
        $stream.Position = $stream.Length - $tailSize
        $tail = $reader.ReadBytes($tailSize)
        $end = -1
        for ($i = $tail.Length - 22; $i -ge 0; $i--) {
            if ([System.BitConverter]::ToUInt32($tail, $i) -eq 0x06054b50) { $end = $i; break }
        }
        if ($end -lt 0) { throw 'ZIP directory footer not found' }
        $count = [System.BitConverter]::ToUInt16($tail, $end + 10)
        $directory = [long][System.BitConverter]::ToUInt32($tail, $end + 16)
        for ($entry = 0; $entry -lt $count; $entry++) {
            $stream.Position = $directory
            if ($reader.ReadUInt32() -ne 0x02014b50) { throw 'Invalid ZIP directory entry' }
            $stream.Position = $directory + 28
            $nameLength = $reader.ReadUInt16()
            $extraLength = $reader.ReadUInt16()
            $commentLength = $reader.ReadUInt16()
            $stream.Position = $directory + 42
            $local = [long]$reader.ReadUInt32()
            foreach ($namePosition in @(($directory + 46), ($local + 30))) {
                $stream.Position = $namePosition
                $name = $reader.ReadBytes($nameLength)
                $changed = $false
                for ($i = 0; $i -lt $name.Length; $i++) {
                    if ($name[$i] -eq 92) { $name[$i] = 47; $changed = $true }
                }
                if ($changed) { $stream.Position = $namePosition; $stream.Write($name, 0, $name.Length) }
            }
            $directory += 46 + $nameLength + $extraLength + $commentLength
        }
    }
    finally { $stream.Dispose() }
}
