//
// Created by zhenxi on 2022/2/6.
//

#include "../includes/invokePrintf.h"

#include "HookUtils.h"
#include "AllInclude.h"
#include "ZhenxiLog.h"
#include "logging.h"
#include "dlfcn_nougat.h"
#include "dlfcn_compat.h"
#include "libpath.h"


static std::ofstream *invokeOs;
static bool isSave = false;

std::string (*invokePrintf_org_PrettyMethodSym)(void *thiz, bool b) = nullptr;

HOOK_DEF(void*, invoke, void *thiz, void *self, uint32_t *args, uint32_t args_size, void *result,
         const char *shorty) {

    string basicString = invokePrintf_org_PrettyMethodSym(thiz, true);

    LOG(INFO) << "invoke method info -> " << basicString;

    if (isSave) {
        *invokeOs << basicString.append("\n");
    }
    return orig_invoke(thiz, self, args, args_size, result, shorty);
}


void invokePrintf::HookJNIInvoke(JNIEnv *env,
                                 std::ofstream *os,
                                 std::string(*prettyMethodSym)(void *, bool)) {
    if (os != nullptr) {
        invokeOs = os;
        isSave = true;
    }
    if (invokePrintf_org_PrettyMethodSym == nullptr) {
        invokePrintf_org_PrettyMethodSym = prettyMethodSym;
    }
    //artmethod->invoke
    void *invokeSym = getSymCompat(getlibArtPath(),
                                   "_ZN3art9ArtMethod6InvokeEPNS_6ThreadEPjjPNS_6JValueEPKc");
    if (invokeSym == nullptr) {
        LOGE(">>>>>>>>> hook art method invoke fail ")
        return;
    }
    bool isSuccess = HookUtils::Hooker(invokeSym,
                                       (void *) new_invoke,
                                       (void **) &orig_invoke);
    LOGE(">>>>>>>>> hook art method invoke success ! %s ", isSuccess ? "true" : "false")
}

HOOK_DEF(void*, RegisterNative, void *thiz, void *native_method) {
    string basicString = invokePrintf_org_PrettyMethodSym(thiz, true);
    if (isSave) {
        *invokeOs << basicString.append("\n");
    }
    LOG(ERROR) << ">>>>>>>>>>>>>> native register " <<
               basicString.c_str() << "  " << native_method;
    return orig_RegisterNative(thiz, native_method);
}

void invokePrintf::HookJNIRegisterNative(JNIEnv *env,
                                         std::ofstream *os,
                                         std::string(*prettyMethodSym)(void *, bool)) {
    if (os != nullptr) {
        invokeOs = os;
        isSave = true;
    }
    if (invokePrintf_org_PrettyMethodSym == nullptr) {
        invokePrintf_org_PrettyMethodSym = prettyMethodSym;
    }
    //artmethod->RegisterNative
    void *registerNativeSym =
            getSymCompat(getlibArtPath(),
                         "_ZN3art9ArtMethod14RegisterNativeEPKv");
    if (registerNativeSym == nullptr) {
        LOGE(">>>>>>>>> hook art method invoke fail ")
        return;
    }
    bool isSuccess = HookUtils::Hooker(registerNativeSym,
                                       (void *) new_RegisterNative,
                                       (void **) &orig_RegisterNative);
    LOGE(">>>>>>>>> hook art method register nativeS success ! %s ", isSuccess ? "true" : "false")
}