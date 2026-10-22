use airgestures_bridge::recorder::Recorder;
use band_core::events::EmgConfig;
use band_core::proto::{field_bytes, field_int};

const CONFIG: EmgConfig = EmgConfig {
    sample_rate: 2048,
    channels: 8,
    adc_bits: 16,
    samples_per_batch: 16,
    encoding: 0,
};

fn batch(sequence: u64) -> Vec<u8> {
    let values: Vec<u8> = (0..128u16).flat_map(|v| (v + sequence as u16).to_le_bytes()).collect();
    [field_int(1, sequence), field_int(2, sequence * 7812), field_bytes(3, &values)].concat()
}

#[test]
fn a_recording_keeps_every_batch_counts_the_missing_ones_and_labels_moments() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("session");
    let mut recorder = Recorder::create(path.to_str().unwrap()).unwrap();
    // Before the layout is known a batch can't be read.
    recorder.batch(&batch(1), 0.5).unwrap();
    recorder.configure(CONFIG).unwrap();
    recorder.mark("prompt", "a", 1.0).unwrap();
    for sequence in [10, 11, 14] {
        recorder.batch(&batch(sequence), 1.0 + sequence as f64 / 128.0).unwrap();
    }
    recorder.mark("gesture", "index tap, singleTap", 1.2).unwrap();
    let stats = recorder.finish().unwrap();
    assert_eq!((stats.batches, stats.missing, stats.unreadable, stats.marks), (3, 2, 1, 2));

    let emg = std::fs::read(path.join("emg.bin")).unwrap();
    let header = b"AGEMG1 2048 8 16\n";
    assert_eq!(&emg[..header.len()], header);
    let records = &emg[header.len()..];
    assert_eq!(records.len(), 3 * (24 + 256));
    let second = &records[280..560];
    assert_eq!(f64::from_le_bytes(second[..8].try_into().unwrap()), 1.0 + 11.0 / 128.0);
    assert_eq!(u64::from_le_bytes(second[8..16].try_into().unwrap()), 11);
    assert_eq!(u16::from_le_bytes([second[24], second[25]]), 11);

    let marks = std::fs::read_to_string(path.join("marks.csv")).unwrap();
    assert_eq!(marks, "time,event,label\n1.000000,prompt,a\n1.200000,gesture,index tap  singleTap\n");
}
