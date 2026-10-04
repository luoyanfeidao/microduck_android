use std::{env,fs,path::PathBuf};
fn main(){
    let root=PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let source=root.join("../../../Pollen/microduck/robotd/src/control.rs");
    println!("cargo:rerun-if-changed={}",source.display());
    let out=PathBuf::from(env::var("OUT_DIR").unwrap());
    // Append Android lifecycle hooks inside the same module; upstream bytes stay intact.
    let extension="\nimpl Controller { pub fn android_cancel_skill(&mut self) { self.active=None; } pub fn android_start_roll(&mut self) -> Result<(), &'static str> { if self.active.is_some_and(|a|a.remaining<=0.0) { self.active=None; } if !self.busy() { self.start_skill(0)?; } Ok(()) } }\n";
    fs::write(out.join("control.rs"),fs::read_to_string(source).unwrap()+extension).unwrap();
    fs::write(out.join("control_module.rs"),format!("#[path = {:?}] pub mod control;",out.join("control.rs"))).unwrap();
}
