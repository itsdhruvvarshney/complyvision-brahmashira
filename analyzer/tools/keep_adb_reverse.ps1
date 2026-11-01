# Re-applies adb reverse tcp:8000 for every attached device every 3 s, so re-plugging the cable or
# switching phones never breaks the link to the analyzer.
while ($true) {
    foreach ($line in (adb devices) -split "`n") {
        if ($line -match "^(\S+)\s+device$") {
            $rev = adb -s $Matches[1] reverse --list
            if ($rev -notmatch "tcp:8000") { adb -s $Matches[1] reverse tcp:8000 tcp:8000 | Out-Null }
        }
    }
    Start-Sleep -Seconds 3
}
