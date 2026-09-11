$ErrorActionPreference = "Continue"
git add -A
Write-Output "=== 待提交 ==="
git status --short
git commit -q -F .gitmsg.tmp
Write-Output ("commit exit=" + $LASTEXITCODE)
Remove-Item .gitmsg.tmp -Force

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public class CredMan6 {
  [DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode, EntryPoint="CredReadW")]
  private static extern bool CredRead(string target, int type, int flags, out IntPtr credential);
  [DllImport("advapi32.dll", EntryPoint="CredFree")]
  private static extern void CredFree(IntPtr cred);
  [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
  private struct CREDENTIAL {
    public int Flags; public int Type; public string TargetName; public string Comment;
    public long LastWritten; public int CredentialBlobSize; public IntPtr CredentialBlob;
    public int Persist; public int AttributeCount; public IntPtr Attributes;
    public string TargetAlias; public string UserName; }
  public static byte[] Read(string target) {
    IntPtr p;
    if (!CredRead(target, 1, 0, out p)) return null;
    try {
      CREDENTIAL c = (CREDENTIAL)Marshal.PtrToStructure(p, typeof(CREDENTIAL));
      byte[] b = new byte[c.CredentialBlobSize];
      Marshal.Copy(c.CredentialBlob, b, 0, c.CredentialBlobSize);
      return b;
    } finally { CredFree(p); }
  }
}
'@
$bytes = [CredMan6]::Read("git:https://github.com")
$env:GHTOK = ([Text.Encoding]::Unicode.GetString($bytes)).Trim()
$ask = Join-Path (Get-Location).Path ".gittmp_askpass.cmd"
Set-Content -Path $ask -Value "@echo off`r`necho %GHTOK%`r`n" -Encoding ASCII
$env:GIT_ASKPASS = $ask
$env:GIT_TERMINAL_PROMPT = "0"

Write-Output ""
Write-Output "=== 推送 ==="
git -c credential.helper= -c http.sslBackend=openssl push origin main 2>&1
Write-Output ("push exit=" + $LASTEXITCODE)
Remove-Item $ask -Force -ErrorAction SilentlyContinue