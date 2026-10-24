//! DataX framing inside decrypted AirShield records.
//!
//! Frame layout: `u16be size | u16be channel | [u32be words…] | payload`. The
//! size's top bit says typed words follow; each word with its top bit set is
//! followed by another word.

use crate::error::{Result, perr};

pub fn be16(bytes: &[u8], offset: usize) -> u16 {
    u16::from_be_bytes([bytes[offset], bytes[offset + 1]])
}

pub fn be32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes([
        bytes[offset],
        bytes[offset + 1],
        bytes[offset + 2],
        bytes[offset + 3],
    ])
}

pub fn encode_frame(channel: u16, words: &[u32], payload: &[u8]) -> Result<Vec<u8>> {
    let mut body = Vec::with_capacity(words.len() * 4 + payload.len());
    for word in words {
        body.extend_from_slice(&word.to_be_bytes());
    }
    body.extend_from_slice(payload);
    if body.len() > 0x7fff {
        return Err(perr("DataX frame too large"));
    }
    let size = body.len() as u16 | if words.is_empty() { 0 } else { 0x8000 };
    let mut out = size.to_be_bytes().to_vec();
    out.extend_from_slice(&channel.to_be_bytes());
    out.extend(body);
    Ok(out)
}

/// Debug prints the decrypted payload; never log frames.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DataXFrame {
    pub channel: u16,
    pub words: Vec<u32>,
    pub payload: Vec<u8>,
}

/// A record without its trailing `n` bytes of `0xc0 + n` (1..=16), or whole when it has none.
fn without_padding(record: &[u8]) -> &[u8] {
    let Some(&marker) = record.last() else { return record };
    let count = usize::from(marker.wrapping_sub(0xc0));
    if (1..=16).contains(&count) && count <= record.len() && record[record.len() - count..].iter().all(|&b| b == marker) {
        &record[..record.len() - count]
    } else {
        record
    }
}

/// Reassembles frames that span several decrypted 16-byte-aligned records.
#[derive(Default)]
pub struct DataXReceiver {
    pending: Vec<u8>,
    /// The sender pads every record (a whole block of 0xd0 when its frames end on a block
    /// boundary): each record's padding is then known exactly and comes off before parsing.
    always_padded: bool,
    /// The last few records as received (newest first), for the log when reading goes wrong.
    recent: std::collections::VecDeque<Vec<u8>>,
}

impl std::fmt::Debug for DataXReceiver {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DataXReceiver")
            .field("pending_len", &self.pending.len())
            .finish()
    }
}

impl DataXReceiver {
    /// The sender pads every record (the encryption scheme says so: all but scheme 3), so each
    /// record's padding is known exactly from the start, not only after a whole 0xd0 block.
    pub fn set_always_padded(&mut self) {
        self.always_padded = true;
    }

    /// The last few records as received, newest first. Debug only: they hold sensor data.
    pub fn recent(&self) -> &std::collections::VecDeque<Vec<u8>> {
        &self.recent
    }

    /// Bytes held back waiting for the rest of a frame.
    pub fn pending_len(&self) -> usize {
        self.pending.len()
    }

    /// Feeds one decrypted AirShield record and returns every frame it completes.
    ///
    /// The sender pads a record with `n` bytes of `0xc0 + n` (1..=15) only when
    /// its frames don't fill a whole number of 16-byte blocks, so the tail
    /// alone can't tell padding from data: an aligned record whose last frame
    /// ends in `0xc1` has no padding. The record is therefore appended
    /// unstripped and complete frames are parsed first (padding bytes as a
    /// header always declare a frame far larger than 15 bytes, so they never
    /// start one); only a non-empty leftover after the last complete frame
    /// can end in padding, and the tail rule is applied to that leftover.
    ///
    /// Residual ambiguity: a record ending in a partial frame exactly `n`
    /// bytes short, padded with `n` bytes of `0xc0 + n`, is read as a
    /// complete frame whose last bytes are the padding. Records from
    /// `AirShieldCipher::encrypt` always carry whole frames, so this needs a
    /// sender that splits frames across records and pads the split.
    ///
    /// An error leaves the offending bytes pending; treat it as fatal for the
    /// connection and drop the receiver.
    pub fn feed(&mut self, plaintext: &[u8]) -> Result<Vec<DataXFrame>> {
        if plaintext.is_empty() || !plaintext.len().is_multiple_of(16) {
            return Err(perr("Unaligned DataX plaintext"));
        }
        // Newer firmware also splits frames across records, so the residual ambiguity below
        // happens (seen on build 64 mid-update: the next frame read 4 bytes early). A whole
        // block of 0xd0 shows the sender pads every record; from then on it's unambiguous.
        if !self.always_padded && plaintext.ends_with(&[0xd0; 16]) {
            self.always_padded = true;
        }
        self.recent.push_front(plaintext.to_vec());
        self.recent.truncate(4);
        let record = if self.always_padded { without_padding(plaintext) } else { plaintext };
        self.pending.extend_from_slice(record);
        let mut frames = Vec::new();
        while self.pending.len() >= 4 {
            let length = be16(&self.pending, 0);
            let size = usize::from(length & 0x7fff) + 4;
            if self.pending.len() < size {
                break;
            }
            let channel = be16(&self.pending, 2);
            let mut offset = 4;
            let mut words = Vec::new();
            if length & 0x8000 != 0 {
                loop {
                    if offset + 4 > size {
                        return Err(perr("Truncated DataX typed header"));
                    }
                    let word = be32(&self.pending, offset);
                    words.push(word);
                    offset += 4;
                    if word & 0x8000_0000 == 0 {
                        break;
                    }
                }
            }
            frames.push(DataXFrame {
                channel,
                words,
                payload: self.pending[offset..size].to_vec(),
            });
            self.pending.drain(..size);
        }
        if !self.always_padded {
            self.strip_padding();
        }
        Ok(frames)
    }

    /// Only the repeated suffix observed on the tested firmware is stripped,
    /// and only from bytes left over after the last complete frame.
    fn strip_padding(&mut self) {
        let Some(&marker) = self.pending.last() else {
            return;
        };
        let count = usize::from(marker.wrapping_sub(0xc0));
        // Newer firmware pads every record by 1..=16 bytes: a frame that ends on a
        // block boundary is followed by a whole block of 0xd0 (seen on hardware).
        if !(1..=16).contains(&count) || count > self.pending.len() {
            return;
        }
        let tail = self.pending.len() - count;
        if self.pending[tail..].iter().all(|&byte| byte == marker) {
            self.pending.truncate(tail);
        }
    }
}
