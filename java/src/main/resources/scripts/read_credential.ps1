param([string]$TargetName)

$sig = @'
[DllImport("advapi32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
public static extern bool CredRead(string target, int type, int flags, out IntPtr credentialPtr);
[DllImport("advapi32.dll", SetLastError=true)]
public static extern void CredFree(IntPtr cred);
[StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)]
public struct CREDENTIAL {
  public int Flags;
  public int Type;
  public IntPtr TargetName;
  public IntPtr Comment;
  public long LastWritten;
  public int CredentialBlobSize;
  public IntPtr CredentialBlob;
  public int Persist;
  public int AttributeCount;
  public IntPtr Attributes;
  public IntPtr TargetAlias;
  public IntPtr UserName;
}
'@

Add-Type -MemberDefinition $sig -Namespace Win32 -Name Cred

$ptr = [IntPtr]::Zero
# CRED_TYPE_GENERIC = 1
$ok = [Win32.Cred]::CredRead($TargetName, 1, 0, [ref]$ptr)
if (-not $ok) {
  exit 1
}

try {
  $cred = [System.Runtime.InteropServices.Marshal]::PtrToStructure($ptr, [type][Win32.Cred+CREDENTIAL])
  $bytes = New-Object byte[] $cred.CredentialBlobSize
  [System.Runtime.InteropServices.Marshal]::Copy($cred.CredentialBlob, $bytes, 0, $cred.CredentialBlobSize)
  [System.Text.Encoding]::Unicode.GetString($bytes)
} finally {
  [Win32.Cred]::CredFree($ptr)
}
