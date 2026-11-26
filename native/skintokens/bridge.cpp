#include <jni.h>
#include <skintokens/skintokens.hpp>
#include <cmath>
#include <cstdint>
#include <exception>
#include <stdexcept>
#include <vector>

namespace {
void fail(JNIEnv* env,const char* message) {
    if(!env->ExceptionCheck()) {
        auto cls=env->FindClass("java/lang/IllegalStateException");
        if(cls) env->ThrowNew(cls,message);
    }
}
struct UtfChars {
    JNIEnv* env; jstring string; const char* chars;
    ~UtfChars(){if(chars) env->ReleaseStringUTFChars(string,chars);}
};
}

// JNI version and packet format are owned by ExtrusionExtraction, not the upstream ABI.
extern "C" JNIEXPORT jint JNICALL
Java_com_hereliesaz_extrusionextraction_shared_SkinTokensEngine_nativeAbi(JNIEnv*,jobject) {return 1;}

namespace {
jfloatArray predictRig(JNIEnv* env,jstring bundle,jfloatArray positions,jintArray triangles,
                       skintokens::device_kind device) {
    try {
        if(!bundle||!positions||!triangles) throw std::invalid_argument("Missing learned rig input.");
        const auto np=env->GetArrayLength(positions),nt=env->GetArrayLength(triangles);
        if(np<9||np%3||nt<3||nt%3||np>3000000||nt>6000000)
            throw std::invalid_argument("Mesh exceeds native rig input limits.");
        const auto vertices=static_cast<std::size_t>(np/3);
        UtfChars path{env,bundle,env->GetStringUTFChars(bundle,nullptr)};
        if(!path.chars) return nullptr;
        std::vector<jfloat> xyz(static_cast<std::size_t>(np));
        std::vector<jint> ids(static_cast<std::size_t>(nt));
        env->GetFloatArrayRegion(positions,0,np,xyz.data());
        env->GetIntArrayRegion(triangles,0,nt,ids.data());
        if(env->ExceptionCheck()) return nullptr;
        skintokens::mesh mesh;
        mesh.vertices.reserve(vertices);
        for(std::size_t i=0;i<xyz.size();i+=3) {
            if(!std::isfinite(xyz[i])||!std::isfinite(xyz[i+1])||!std::isfinite(xyz[i+2]))
                throw std::invalid_argument("Non-finite native mesh position.");
            mesh.vertices.push_back({xyz[i],xyz[i+1],xyz[i+2]});
        }
        mesh.faces.reserve(ids.size()/3);
        for(std::size_t i=0;i<ids.size();i+=3) {
            skintokens::triangle face{};
            for(std::size_t k=0;k<3;++k) {
                if(ids[i+k]<0||static_cast<std::size_t>(ids[i+k])>=vertices)
                    throw std::invalid_argument("Native mesh index out of range.");
                face[k]=static_cast<std::uint32_t>(ids[i+k]);
            }
            mesh.faces.push_back(face);
        }
        skintokens::runtime_options runtime;
        runtime.device=device;
        runtime.threads=4;
        auto model=skintokens::model::load(path.chars,runtime);
        if(!model) throw std::runtime_error(model.error().message);
        skintokens::generation_options generation;
        generation.beams=1; // bounded initial mobile baseline; quality must be benchmarked.
        auto skin=model->rig(mesh,generation);
        if(!skin) throw std::runtime_error(skin.error().message);
        if(!skin->learned) throw std::runtime_error("Native rig did not return learned weights.");
        const auto count=skin->rig.parents.size();
        if(count==0||count>65535||skin->rig.rest_positions.size()!=count||
           skin->joints.size()!=vertices||skin->weights.size()!=vertices)
            throw std::runtime_error("Native rig shape mismatch.");
        std::vector<jfloat> packet;
        packet.reserve(2+4*count+8*vertices);
        packet.push_back(1);packet.push_back(static_cast<float>(count));
        for(std::size_t i=0;i<count;++i) {
            const auto p=skin->rig.rest_positions[i];
            packet.push_back(static_cast<float>(skin->rig.parents[i]));
            packet.push_back(p.x);packet.push_back(p.y);packet.push_back(p.z);
        }
        for(std::size_t i=0;i<vertices;++i) {
            for(auto id:skin->joints[i]) packet.push_back(static_cast<float>(id));
            for(auto w:skin->weights[i]) packet.push_back(w);
        }
        auto result=env->NewFloatArray(static_cast<jsize>(packet.size()));
        if(result) env->SetFloatArrayRegion(result,0,static_cast<jsize>(packet.size()),packet.data());
        return result;
    } catch(const std::exception& e) {fail(env,e.what());}
      catch(...) {fail(env,"Unknown SkinTokens native failure.");}
    return nullptr;
}

// Keep the existing CPU entry point and packet ABI. The optional Vulkan entry point
// selects the pinned upstream's strict Vulkan device kind, not automatic fallback.
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_extrusionextraction_shared_SkinTokensEngine_predict(
    JNIEnv* env,jobject,jstring bundle,jfloatArray positions,jintArray triangles) {
    return predictRig(env,bundle,positions,triangles,skintokens::device_kind::cpu);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hereliesaz_extrusionextraction_shared_SkinTokensEngine_nativeVulkanBuilt(JNIEnv*,jobject) {
#ifdef EE_SKINTOKENS_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_hereliesaz_extrusionextraction_shared_SkinTokensEngine_predictVulkan(
    JNIEnv* env,jobject,jstring bundle,jfloatArray positions,jintArray triangles) {
#ifdef EE_SKINTOKENS_VULKAN
    return predictRig(env,bundle,positions,triangles,skintokens::device_kind::vulkan);
#else
    fail(env,"This SkinTokens package has no Vulkan backend.");
    return nullptr;
#endif
}
