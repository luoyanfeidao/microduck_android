#include <jni.h>
#include "rust_control.h"
namespace {
void reject(JNIEnv* env,const char* message){
    if(env->ExceptionCheck())return;
    jclass type=env->FindClass("java/lang/IllegalStateException");
    if(type)env->ThrowNew(type,message);
}
struct Inference {JNIEnv* env; jobject host; jmethodID method;};
int32_t infer(void* context,int32_t net,const float* obs,float* action){
    auto& c=*static_cast<Inference*>(context);auto* env=c.env;
    jfloatArray input=env->NewFloatArray(61);if(!input)return -1;
    env->SetFloatArrayRegion(input,0,61,obs);
    if(env->ExceptionCheck()){env->DeleteLocalRef(input);return -1;}
    auto output=static_cast<jfloatArray>(env->CallObjectMethod(c.host,c.method,net,input));
    env->DeleteLocalRef(input);
    if(env->ExceptionCheck())return -1;
    if(!output || env->GetArrayLength(output)!=14){if(output)env->DeleteLocalRef(output);return -1;}
    env->GetFloatArrayRegion(output,0,14,action);env->DeleteLocalRef(output);
    return env->ExceptionCheck()?-1:0;
}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_microduck_sim_RustControl_open(JNIEnv* env,jclass){
    auto id=duck_core_open();if(!id)reject(env,"Rust core initialization failed");return static_cast<jlong>(id);
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_RustControl_close(JNIEnv* env,jclass,jlong id){
    if(duck_core_close(id))reject(env,"Rust core already closed or wrong worker");
}
extern "C" JNIEXPORT void JNICALL Java_com_microduck_sim_RustControl_command(JNIEnv* env,jclass,jlong id,jint op){
    if(duck_core_command(id,op))reject(env,"Rust core command failed");
}
extern "C" JNIEXPORT jdoubleArray JNICALL Java_com_microduck_sim_RustControl_tick(JNIEnv* env,jclass,jlong id,
    jdoubleArray sensors,jdoubleArray twist,jboolean filter,jobject host){
    if(!sensors||!twist||!host||env->GetArrayLength(sensors)!=40||env->GetArrayLength(twist)!=3){
        reject(env,"Rust core requires sensor40 and twist3");return nullptr;
    }
    double sample[40],velocity[3],output[18];
    env->GetDoubleArrayRegion(sensors,0,40,sample);env->GetDoubleArrayRegion(twist,0,3,velocity);
    if(env->ExceptionCheck())return nullptr;
    jclass cls=env->GetObjectClass(host);if(!cls)return nullptr;
    jmethodID method=env->GetMethodID(cls,"rustInfer","(I[F)[F");env->DeleteLocalRef(cls);
    if(!method)return nullptr;
    Inference context{env,host,method};
    if(duck_core_tick(id,sample,velocity,filter,infer,&context,output)){
        reject(env,"Rust control step failed; body paused");return nullptr;
    }
    jdoubleArray result=env->NewDoubleArray(18);
    if(result)env->SetDoubleArrayRegion(result,0,18,output);
    return result;
}
