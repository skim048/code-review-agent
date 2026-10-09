Run a code review against the given GitHub PR URL by calling the local code-review-agent API.

Execute this PowerShell command and display the review result:

```powershell
$result = Invoke-RestMethod -Uri "http://localhost:8090/review" -Method Post -ContentType "application/json" -Body ('{"prUrl":"' + "$ARGUMENTS" + '"}')
$result.review
```
