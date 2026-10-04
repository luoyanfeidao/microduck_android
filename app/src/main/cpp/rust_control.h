#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef int32_t (*duck_infer_fn)(void*,int32_t,const float*,float*);
uint64_t duck_core_open(void);
int32_t duck_core_close(uint64_t);
int32_t duck_core_command(uint64_t,int32_t);
int32_t duck_core_tick(uint64_t,const double*,const double*,int32_t,duck_infer_fn,void*,double*);
#ifdef __cplusplus
}
#endif
