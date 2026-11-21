//! Structural APK validation only. Android must verify the signing certificate.
use crate::error::{ApiError, Result};
use std::{
    fs::File,
    io::{Read, Seek, SeekFrom},
    path::Path,
};
pub const EXPECTED_PACKAGE: &str = "org.imdtv.iptvtv.managed";
const MAX_MANIFEST: u64 = 2 * 1024 * 1024;
#[derive(Debug)]
pub struct Manifest {
    pub package: String,
    pub version_code: i64,
    pub version_name: String,
}
fn invalid() -> ApiError {
    ApiError::bad("Invalid or unsupported APK / binary Android manifest")
}
fn u16_at(data: &[u8], offset: usize) -> Result<u16> {
    Ok(u16::from_le_bytes(
        data.get(offset..offset + 2)
            .ok_or_else(invalid)?
            .try_into()
            .map_err(|_| invalid())?,
    ))
}
fn u32_at(data: &[u8], offset: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(
        data.get(offset..offset + 4)
            .ok_or_else(invalid)?
            .try_into()
            .map_err(|_| invalid())?,
    ))
}
fn len8(data: &[u8], pos: &mut usize) -> Result<usize> {
    let first = *data.get(*pos).ok_or_else(invalid)?;
    *pos += 1;
    if first & 0x80 == 0 {
        Ok(first as usize)
    } else {
        let second = *data.get(*pos).ok_or_else(invalid)?;
        *pos += 1;
        Ok((((first & 0x7f) as usize) << 8) | second as usize)
    }
}
fn len16(data: &[u8], pos: &mut usize) -> Result<usize> {
    let first = u16_at(data, *pos)?;
    *pos += 2;
    if first & 0x8000 == 0 {
        Ok(first as usize)
    } else {
        let second = u16_at(data, *pos)?;
        *pos += 2;
        Ok((((first & 0x7fff) as usize) << 16) | second as usize)
    }
}
fn strings(chunk: &[u8]) -> Result<Vec<String>> {
    let header = u16_at(chunk, 2)? as usize;
    let count = u32_at(chunk, 8)? as usize;
    let flags = u32_at(chunk, 16)?;
    let start = u32_at(chunk, 20)? as usize;
    if header < 28
        || count > 65536
        || header + count * 4 > chunk.len()
        || start < header + count * 4
        || start > chunk.len()
    {
        return Err(invalid());
    }
    let mut result = Vec::with_capacity(count);
    let mut total = 0usize;
    for i in 0..count {
        let mut pos = start
            .checked_add(u32_at(chunk, header + i * 4)? as usize)
            .ok_or_else(invalid)?;
        let value = if flags & 0x100 != 0 {
            let _utf16_length = len8(chunk, &mut pos)?;
            let length = len8(chunk, &mut pos)?;
            let end = pos.checked_add(length).ok_or_else(invalid)?;
            if chunk.get(end) != Some(&0) {
                return Err(invalid());
            }
            String::from_utf8(chunk.get(pos..end).ok_or_else(invalid)?.to_vec())
                .map_err(|_| invalid())?
        } else {
            let length = len16(chunk, &mut pos)?;
            if length > MAX_MANIFEST as usize / 2 {
                return Err(invalid());
            }
            let end = pos + length * 2;
            if u16_at(chunk, end)? != 0 {
                return Err(invalid());
            }
            let units: Vec<u16> = chunk
                .get(pos..end)
                .ok_or_else(invalid)?
                .chunks_exact(2)
                .map(|v| u16::from_le_bytes([v[0], v[1]]))
                .collect();
            String::from_utf16(&units).map_err(|_| invalid())?
        };
        total += value.len();
        if total > 4 * MAX_MANIFEST as usize {
            return Err(invalid());
        }
        result.push(value);
    }
    Ok(result)
}
pub fn parse_manifest(data: &[u8]) -> Result<Manifest> {
    if data.len() > MAX_MANIFEST as usize
        || u16_at(data, 0)? != 3
        || u16_at(data, 2)? != 8
        || u32_at(data, 4)? as usize != data.len()
    {
        return Err(invalid());
    }
    let mut pos = 8usize;
    let mut pool: Option<Vec<String>> = None;
    while pos < data.len() {
        let kind = u16_at(data, pos)?;
        let header = u16_at(data, pos + 2)? as usize;
        let size = u32_at(data, pos + 4)? as usize;
        if header < 8 || size < header {
            return Err(invalid());
        }
        let end = pos.checked_add(size).ok_or_else(invalid)?;
        let chunk = data.get(pos..end).ok_or_else(invalid)?;
        if kind == 1 {
            if pool.is_some() {
                return Err(invalid());
            }
            pool = Some(strings(chunk)?);
        }
        if kind == 0x0102 {
            if header != 16 {
                return Err(invalid());
            }
            let pool = pool.as_ref().ok_or_else(invalid)?;
            let get = |idx: u32| -> Result<&str> {
                pool.get(idx as usize)
                    .map(|s| s.as_str())
                    .ok_or_else(invalid)
            };
            if get(u32_at(chunk, 20)?)? != "manifest" {
                return Err(invalid());
            }
            let attr_start = 16 + u16_at(chunk, 24)? as usize;
            let attr_size = u16_at(chunk, 26)? as usize;
            let count = u16_at(chunk, 28)? as usize;
            if attr_start < 36
                || attr_size < 20
                || count > 1024
                || attr_start + attr_size * count > chunk.len()
            {
                return Err(invalid());
            }
            let mut package = None;
            let mut version_code = None;
            let mut version_name = None;
            for i in 0..count {
                let at = attr_start + i * attr_size;
                let ns = u32_at(chunk, at)?;
                let name = get(u32_at(chunk, at + 4)?)?;
                let raw = u32_at(chunk, at + 8)?;
                let value_type = *chunk.get(at + 15).ok_or_else(invalid)?;
                let value = u32_at(chunk, at + 16)?;
                let string_value = || -> Result<String> {
                    if raw != u32::MAX {
                        Ok(get(raw)?.to_owned())
                    } else if value_type == 3 {
                        Ok(get(value)?.to_owned())
                    } else {
                        Err(ApiError::bad("Manifest package and versionName must be literal strings, not resource references"))
                    }
                };
                if name == "package" && ns == u32::MAX {
                    if package.is_some() {
                        return Err(invalid());
                    }
                    package = Some(string_value()?);
                }
                if ns != u32::MAX && get(ns)? == "http://schemas.android.com/apk/res/android" {
                    match name {
                        "versionCode" => {
                            if version_code.is_some() || !matches!(value_type, 0x10 | 0x11) {
                                return Err(invalid());
                            }
                            version_code = Some(value as i64);
                        }
                        "versionCodeMajor" if value != 0 => {
                            return Err(ApiError::bad("versionCodeMajor is unsupported"))
                        }
                        "versionName" => {
                            if version_name.is_some() {
                                return Err(invalid());
                            }
                            version_name = Some(string_value()?);
                        }
                        _ => {}
                    }
                }
            }
            return Ok(Manifest {
                package: package.ok_or_else(invalid)?,
                version_code: version_code.ok_or_else(invalid)?,
                version_name: version_name.ok_or_else(invalid)?,
            });
        }
        pos = end;
    }
    Err(invalid())
}

// Preflight the central directory before zip allocates its entries. ZIP64 and
// multi-disk archives are intentionally unsupported for this bounded uploader.
fn preflight(file: &mut File) -> Result<(u64, bool)> {
    let length = file.metadata()?.len();
    if length < 22 {
        return Err(invalid());
    }
    let tail_length = length.min(65557) as usize;
    file.seek(SeekFrom::End(-(tail_length as i64)))?;
    let mut tail = vec![0; tail_length];
    file.read_exact(&mut tail)?;
    let offset = (0..=tail_length - 22)
        .rev()
        .find(|&i| {
            tail.get(i..i + 4) == Some(b"PK\x05\x06")
                && u16_at(&tail, i + 20).is_ok_and(|n| i + 22 + n as usize == tail.len())
        })
        .ok_or_else(invalid)?;
    let count = u16_at(&tail, offset + 10)?;
    let central_size = u32_at(&tail, offset + 12)? as u64;
    let central = u32_at(&tail, offset + 16)? as u64;
    if u16_at(&tail, offset + 4)? != 0
        || u16_at(&tail, offset + 6)? != 0
        || u16_at(&tail, offset + 8)? != count
        || count == 0
        || count > 20000
        || central_size > 8 * 1024 * 1024
        || central + central_size != length - tail_length as u64 + offset as u64
    {
        return Err(invalid());
    }
    let mut signing_block = false;
    if central >= 24 {
        file.seek(SeekFrom::Start(central - 24))?;
        let mut footer = [0u8; 24];
        file.read_exact(&mut footer)?;
        if &footer[8..] == b"APK Sig Block 42" {
            let size = u64::from_le_bytes(footer[..8].try_into().map_err(|_| invalid())?);
            if size >= 24 && size <= central - 8 {
                file.seek(SeekFrom::Start(central - size - 8))?;
                let mut head = [0u8; 8];
                file.read_exact(&mut head)?;
                signing_block = u64::from_le_bytes(head) == size;
            }
        }
    }
    file.seek(SeekFrom::Start(0))?;
    Ok((count as u64, signing_block))
}
pub fn validate(path: &Path, expected_code: i64, expected_name: &str) -> Result<Manifest> {
    let mut file = File::open(path)?;
    let (count, signing_block) = preflight(&mut file)?;
    let mut archive = zip::ZipArchive::new(file).map_err(|_| invalid())?;
    if archive.len() as u64 != count {
        return Err(invalid());
    }
    let mut manifest = None;
    let mut dex = false;
    let mut v1_signature = false;
    let mut v1_manifest = false;
    let mut names = std::collections::HashSet::new();
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|_| invalid())?;
        let name = entry.name().to_owned();
        if name.len() > 1024
            || name.starts_with('/')
            || name.contains('\\')
            || name.split('/').any(|part| part == "..")
            || !names.insert(name.clone())
        {
            return Err(invalid());
        }
        if name == "classes.dex" && entry.size() > 0 {
            dex = true;
        }
        if name == "META-INF/MANIFEST.MF" {
            v1_manifest = true;
        }
        if name.starts_with("META-INF/")
            && [".RSA", ".DSA", ".EC"]
                .iter()
                .any(|suffix| name.ends_with(suffix))
            && entry.size() > 0
        {
            v1_signature = true;
        }
        if name == "AndroidManifest.xml" {
            if entry.size() > MAX_MANIFEST {
                return Err(ApiError::bad("APK manifest exceeds the 2 MiB safety limit"));
            }
            let mut data = Vec::new();
            entry
                .by_ref()
                .take(MAX_MANIFEST + 1)
                .read_to_end(&mut data)
                .map_err(|_| invalid())?;
            manifest = Some(parse_manifest(&data)?);
        }
    }
    if !dex || !(signing_block || (v1_signature && v1_manifest)) {
        return Err(ApiError::bad("APK must contain classes.dex and signing artifacts. Their presence is not cryptographic signature verification."));
    }
    let manifest = manifest.ok_or_else(invalid)?;
    if manifest.package != EXPECTED_PACKAGE {
        return Err(ApiError::bad(format!(
            "APK package must be {EXPECTED_PACKAGE}"
        )));
    }
    if manifest.version_code != expected_code || manifest.version_name != expected_name {
        return Err(ApiError::bad(format!(
            "APK manifest reports versionCode {} and versionName {}; these must match the form",
            manifest.version_code, manifest.version_name
        )));
    }
    Ok(manifest)
}
