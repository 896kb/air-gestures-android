//! Minimal protobuf reader/writer (kinesis `BandWire.swift`: `ProtoFields`, `BandWire.field`).

use std::collections::HashMap;

use crate::error::{Result, perr};

#[derive(Debug, Clone)]
enum Value {
    Integer(u64),
    Bytes(Vec<u8>),
    Fixed(Vec<u8>),
}

/// All fields of one protobuf message, keyed by field number.
#[derive(Clone, Default)]
pub struct ProtoFields {
    values: HashMap<u32, Vec<Value>>,
}

/// Prints only the sorted set of field numbers present; field values may be
/// plaintext and are never included.
impl std::fmt::Debug for ProtoFields {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut fields: Vec<u32> = self.values.keys().copied().collect();
        fields.sort_unstable();
        f.debug_struct("ProtoFields")
            .field("fields", &fields)
            .finish()
    }
}

fn read_varint(data: &[u8], offset: &mut usize) -> Result<u64> {
    let mut value = 0u64;
    let mut shift = 0u32;
    while shift <= 63 {
        let Some(&byte) = data.get(*offset) else {
            return Err(perr("Truncated protobuf varint"));
        };
        *offset += 1;
        if shift == 63 && byte > 1 {
            return Err(perr("Protobuf varint overflow"));
        }
        value |= u64::from(byte & 127) << shift;
        if byte < 128 {
            return Ok(value);
        }
        shift += 7;
    }
    Err(perr("Unterminated protobuf varint"))
}

impl ProtoFields {
    pub fn parse(data: &[u8]) -> Result<Self> {
        let mut values: HashMap<u32, Vec<Value>> = HashMap::new();
        let mut offset = 0usize;
        while offset < data.len() {
            let tag = read_varint(data, &mut offset)?;
            let number = tag >> 3;
            if number == 0 || number >= 1 << 29 {
                return Err(perr("Invalid protobuf field number"));
            }
            let number = number as u32;
            let wire = tag & 7;
            if wire == 0 {
                let integer = read_varint(data, &mut offset)?;
                values
                    .entry(number)
                    .or_default()
                    .push(Value::Integer(integer));
                continue;
            }
            let size = match wire {
                1 => 8,
                2 => read_varint(data, &mut offset)?,
                5 => 4,
                _ => return Err(perr("Unsupported protobuf wire type")),
            };
            if size > (data.len() - offset) as u64 {
                return Err(perr("Truncated protobuf field"));
            }
            let size = size as usize;
            let value = if wire == 2 {
                Value::Bytes(data[offset..offset + size].to_vec())
            } else {
                Value::Fixed(data[offset..offset + size].to_vec())
            };
            values.entry(number).or_default().push(value);
            offset += size;
        }
        Ok(Self { values })
    }

    /// The single integer in `field`, or 0 when the field is absent.
    pub fn integer(&self, field: u32) -> Result<u64> {
        match self.values.get(&field) {
            None => Ok(0),
            Some(values) => match values.as_slice() {
                [Value::Integer(integer)] => Ok(*integer),
                _ => Err(perr("Expected protobuf integer")),
            },
        }
    }

    pub fn required_integer(&self, field: u32) -> Result<u64> {
        if !self.values.contains_key(&field) {
            return Err(perr("Missing protobuf integer"));
        }
        self.integer(field)
    }

    /// The single length-delimited value in `field`.
    pub fn bytes(&self, field: u32) -> Result<&[u8]> {
        match self.values.get(&field).map(Vec::as_slice) {
            Some([Value::Bytes(bytes)]) => Ok(bytes),
            _ => Err(perr("Invalid protobuf byte field")),
        }
    }

    /// Like [`bytes`](Self::bytes) but also requires an exact length.
    pub fn bytes_len(&self, field: u32, count: usize) -> Result<&[u8]> {
        let bytes = self.bytes(field)?;
        if bytes.len() != count {
            return Err(perr("Invalid protobuf byte field"));
        }
        Ok(bytes)
    }

    pub fn contains(&self, field: u32) -> bool {
        self.values.contains_key(&field)
    }

    /// Every length-delimited value (strings and nested messages), with its field number.
    pub fn byte_fields(&self) -> Vec<(u32, &[u8])> {
        let mut out: Vec<(u32, &[u8])> = self
            .values
            .iter()
            .flat_map(|(&field, values)| {
                values.iter().filter_map(move |value| match value {
                    Value::Bytes(bytes) => Some((field, bytes.as_slice())),
                    _ => None,
                })
            })
            .collect();
        out.sort_by_key(|(field, _)| *field);
        out
    }

    /// A string field (`None` when absent, repeated or not text).
    pub fn text(&self, field: u32) -> Option<String> {
        self.bytes(field).ok().and_then(|bytes| std::str::from_utf8(bytes).ok()).map(str::to_owned)
    }

    /// Every field with its value, sorted: `1=7 3=[12B 0a0b…] 9=f32:0.25`. For the
    /// stream probe only: values may be plaintext, so never call it on sensor data
    /// or keys. Byte fields show their first [`DESCRIBE_BYTES`] bytes.
    pub fn describe(&self) -> String {
        self.describe_nested(0)
    }

    /// [`describe`](Self::describe), opening byte fields that parse as messages `{…}` (up to
    /// [`DESCRIBE_DEPTH`] deep) and showing printable ones as text. A guess: some bytes parse
    /// as protobuf by chance.
    pub fn describe_nested(&self, depth: usize) -> String {
        let mut fields: Vec<_> = self.values.iter().collect();
        fields.sort_unstable_by_key(|(number, _)| **number);
        let mut parts = Vec::new();
        for (number, values) in fields {
            for value in values {
                let shown = match value {
                    Value::Integer(integer) => integer.to_string(),
                    Value::Bytes(bytes) if !bytes.is_empty() && bytes.iter().all(|b| (0x20..0x7f).contains(b)) => {
                        format!("{:?}", String::from_utf8_lossy(bytes))
                    }
                    Value::Bytes(bytes) if depth < DESCRIBE_DEPTH && !bytes.is_empty() && let Ok(inner) = Self::parse(bytes) => {
                        format!("{{{}}}", inner.describe_nested(depth + 1))
                    }
                    Value::Bytes(bytes) => {
                        let head: String = bytes.iter().take(DESCRIBE_BYTES).map(|b| format!("{b:02x}")).collect();
                        let more = if bytes.len() > DESCRIBE_BYTES { "…" } else { "" };
                        format!("[{}B {head}{more}]", bytes.len())
                    }
                    Value::Fixed(bytes) if bytes.len() == 4 => {
                        let raw = [bytes[0], bytes[1], bytes[2], bytes[3]];
                        format!("f32:{} u32:{}", f32::from_le_bytes(raw), u32::from_le_bytes(raw))
                    }
                    Value::Fixed(bytes) => {
                        let mut raw = [0u8; 8];
                        raw.copy_from_slice(bytes);
                        format!("f64:{} u64:{}", f64::from_le_bytes(raw), u64::from_le_bytes(raw))
                    }
                };
                parts.push(format!("{number}={shown}"));
            }
        }
        parts.join(" ")
    }
}

/// How many bytes of a byte field [`ProtoFields::describe`] shows.
pub const DESCRIBE_BYTES: usize = 24;
/// How deep [`ProtoFields::describe_nested`] opens nested messages.
pub const DESCRIBE_DEPTH: usize = 4;

pub fn varint(mut value: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    while value >= 128 {
        bytes.push((value & 127) as u8 | 128);
        value >>= 7;
    }
    bytes.push(value as u8);
    bytes
}

pub fn field_int(number: u32, value: u64) -> Vec<u8> {
    let mut out = varint(u64::from(number) << 3);
    out.extend(varint(value));
    out
}

pub fn field_bytes(number: u32, bytes: &[u8]) -> Vec<u8> {
    let mut out = varint(u64::from(number) << 3 | 2);
    out.extend(varint(bytes.len() as u64));
    out.extend_from_slice(bytes);
    out
}
