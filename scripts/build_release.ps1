param()
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$taskSigning = [IO.Path]::GetFullPath((Join-Path $taskRoot '.signing'))
if (-not $taskSigning.StartsWith($taskRoot + [IO.Path]::DirectorySeparatorChar)) { throw 'Invalid signing path' }
New-Item -ItemType Directory -Force -Path $taskSigning | Out-Null
$taskPasswordFile = Join-Path $taskSigning 'password.xml'
$taskKey = Join-Path $taskSigning 'owlwhisp-mobile.jks'
if (-not (Test-Path -LiteralPath $taskPasswordFile)) {
    if (Test-Path -LiteralPath $taskKey) { throw 'Existing keystore has no local credential; restore its credential first.' }
    $taskRandom = New-Object byte[] 32
    $taskRng = [Security.Cryptography.RandomNumberGenerator]::Create()
    $taskRng.GetBytes($taskRandom); $taskRng.Dispose()
    $taskSecure = ConvertTo-SecureString ([Convert]::ToBase64String($taskRandom)) -AsPlainText -Force
    $taskSecure | Export-Clixml -LiteralPath $taskPasswordFile
}
$taskSecure = Import-Clixml -LiteralPath $taskPasswordFile
$taskPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($taskSecure)
try {
    $env:OWLWHISP_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($taskPointer)
    $env:OWLWHISP_KEY_PASSWORD = $env:OWLWHISP_STORE_PASSWORD
    $env:OWLWHISP_KEYSTORE = $taskKey
    $env:OWLWHISP_KEY_ALIAS = 'owlwhisp'
    if (-not (Test-Path -LiteralPath $taskKey)) {
        & keytool -genkeypair -keystore $taskKey -storetype PKCS12 -alias owlwhisp -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Fil-Shtopor, O=OwlWhisp' -storepass:env OWLWHISP_STORE_PASSWORD -keypass:env OWLWHISP_KEY_PASSWORD
        if ($LASTEXITCODE -ne 0) { throw 'Keystore creation failed' }
    }
    Push-Location (Join-Path $taskRoot 'android')
    try {
        & .\gradlew.bat :app:assembleRelease :app:lintRelease --no-daemon --console plain
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed' }
    } finally { Pop-Location }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($taskPointer)
    Remove-Item Env:\OWLWHISP_STORE_PASSWORD, Env:\OWLWHISP_KEY_PASSWORD, Env:\OWLWHISP_KEYSTORE, Env:\OWLWHISP_KEY_ALIAS -ErrorAction SilentlyContinue
}

