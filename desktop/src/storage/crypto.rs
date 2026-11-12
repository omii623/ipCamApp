//! Jelszavak titkosítása a Windows DPAPI-val (az Android Keystore megfelelője):
//! a kulcs a Windows-felhasználói fiókhoz kötött, más felhasználó / gép nem tudja visszafejteni.
//! Kimenet: Base64(DPAPI blob).

use base64::{Engine, engine::general_purpose::STANDARD as B64};

const ENTROPY: &[u8] = b"ipCamDesktop-pw";

#[cfg(windows)]
mod imp {
    use super::ENTROPY;
    use std::ptr::{null, null_mut};
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
    };

    fn blob(data: &[u8]) -> CRYPT_INTEGER_BLOB {
        CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 }
    }

    unsafe fn take(out: CRYPT_INTEGER_BLOB) -> Vec<u8> {
        unsafe {
            let v = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
            LocalFree(out.pbData as _);
            v
        }
    }

    pub fn protect(data: &[u8]) -> Option<Vec<u8>> {
        let input = blob(data);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: null_mut() };
        let ok = unsafe {
            CryptProtectData(&input, null(), &entropy, null(), null(), CRYPTPROTECT_UI_FORBIDDEN, &mut out)
        };
        if ok == 0 { None } else { Some(unsafe { take(out) }) }
    }

    pub fn unprotect(data: &[u8]) -> Option<Vec<u8>> {
        let input = blob(data);
        let entropy = blob(ENTROPY);
        let mut out = CRYPT_INTEGER_BLOB { cbData: 0, pbData: null_mut() };
        let ok = unsafe {
            CryptUnprotectData(
                &input,
                null_mut(),
                &entropy,
                null(),
                null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut out,
            )
        };
        if ok == 0 { None } else { Some(unsafe { take(out) }) }
    }
}

#[cfg(not(windows))]
mod imp {
    pub fn protect(_: &[u8]) -> Option<Vec<u8>> {
        None
    }
    pub fn unprotect(_: &[u8]) -> Option<Vec<u8>> {
        None
    }
}

/// Titkosítás; hiba esetén None (a hívó dönti el, mi legyen – NEM szabad üresként menteni).
pub fn encrypt(plain: &str) -> Option<String> {
    imp::protect(plain.as_bytes()).map(|b| B64.encode(b))
}

/// Visszafejtés; hiba esetén None (pl. más felhasználó / gép titkosította).
pub fn decrypt(data: &str) -> Option<String> {
    let bytes = B64.decode(data).ok()?;
    String::from_utf8(imp::unprotect(&bytes)?).ok()
}

#[cfg(all(test, windows))]
mod tests {
    #[test]
    fn roundtrip() {
        let enc = super::encrypt("titkos jelszó").unwrap();
        assert_ne!(enc, "titkos jelszó");
        assert_eq!(super::decrypt(&enc).as_deref(), Some("titkos jelszó"));
        assert_eq!(super::decrypt("bm90LWEtYmxvYg=="), None);
    }
}
