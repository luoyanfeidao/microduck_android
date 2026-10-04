// Compile the real upstream RemoteIo and RobotIo definitions without robotd's unrelated hardware deps.
#![allow(dead_code)]
mod model { pub const NUM_JOINTS: usize = 15; }
#[path = "../../../../Pollen/microduck/duck-control/src/imu.rs"] mod imu;
#[path = "../../../../Pollen/microduck/duck-control/src/io.rs"] mod io;
#[path = "../../../../Pollen/microduck/duck-control/src/sim.rs"] mod sim;
use io::{JointTargets, RobotIo};
use std::{thread::sleep,time::Duration};

fn main() {
    let addr=std::env::args().nth(1).expect("body address");
    let mut body=sim::RemoteIo::at(&addr);
    let initial=body.read().expect("read + handshake");
    assert_eq!(initial.positions[9],0.0);
    assert!(!body.measures_load());assert!(body.measures_velocity());
    assert!((initial.imu.gravity.iter().map(|x|x*x).sum::<f64>()-1.0).abs()<1e-8);
    body.set_gain(160).expect("gain");body.set_torque(true).expect("torque on");
    let mut targets=initial.positions;
    for (i,q) in targets.iter_mut().enumerate(){*q+=0.02*(i as f64).sin();}
    targets[9]=0.8; // non-zero mouth must not shift the right-leg wire slots
    let mut moved=false;
    for _ in 0..50 {
        body.write(&JointTargets::new(targets)).expect("targets");
        let sensors=body.read().expect("sensors");
        assert!(sensors.positions.iter().chain(sensors.velocities.iter()).all(|x|x.is_finite()));
        assert_eq!(sensors.positions[9],0.0);
        moved|=sensors.positions.iter().zip(initial.positions).any(|(a,b)|(a-b).abs()>1e-5);
        sleep(Duration::from_millis(20));
    }
    assert!(moved,"real JNI body did not move");
    let slow=body.slow_sensors().expect("slow sensors");
    assert_eq!(slow.volts,7.4);assert_eq!(slow.temps_c,[32.0;15]);
    body.set_torque(false).expect("torque off");
    body.read().expect("read limp body");drop(body);
    sleep(Duration::from_millis(100));
    let mut reconnected=sim::RemoteIo::at(&addr);
    let stopped=reconnected.read().expect("reconnect handshake");
    sleep(Duration::from_millis(40));
    assert_eq!(stopped.positions,reconnected.read().expect("frozen until reenabled").positions);
    drop(reconnected);
    println!("{{\"real_upstream_remoteio\":true,\"jni_bam_physics\":true,\"fifteen_slots_mouth_zero\":true,\"gain_torque_slow\":true,\"reconnect_stays_paused\":true}}");
}
