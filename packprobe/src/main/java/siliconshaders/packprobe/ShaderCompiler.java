package siliconshaders.packprobe;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.util.spvc.Spv.*;
import static org.lwjgl.util.spvc.Spvc.*;

public final class ShaderCompiler {
    public record Compiled(String glsl, byte[] spirv, String metal, String entryPoint, List<Map<String, Object>> buffers, List<Map<String, Object>> textures, Map<String,Object> interfaces) { }

    private ShaderCompiler() { }

    public static String preprocess(String source,String name) {
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try {
            if(compiler==0 || options==0) throw new IllegalStateException("shaderc initialization failed");
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_1);
            result=invoke(compiler,GlslAdapter.prepare(source),shaderc_fragment_shader,name,options,true);
            validate(result,name+" preprocess");
            return MemoryUtil.memUTF8(shaderc_result_get_bytes(result));
        } finally {
            if(result!=0) shaderc_result_release(result);
            if(options!=0) shaderc_compile_options_release(options);
            if(compiler!=0) shaderc_compiler_release(compiler);
        }
    }

    public static Compiled compile(String expanded, String name, boolean vertex) {
        return compile(expanded,name,vertex?shaderc_vertex_shader:shaderc_fragment_shader,false);
    }

    /** Offline translation only. Null means the default preprocessor removed the entire program. */
    public static Compiled compileScene(String expanded,String name) {
        int stage=name.endsWith(".vsh")?shaderc_vertex_shader:name.endsWith(".fsh")?shaderc_fragment_shader:shaderc_compute_shader;
        return compile(expanded,name,stage,true);
    }

    private static Compiled compile(String expanded,String name,int stage,boolean scene) {
        boolean vertex=stage==shaderc_vertex_shader;
        long compiler = shaderc_compiler_initialize(), options = shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) throw new IllegalStateException("shaderc initialization failed");
        byte[] spv;
        String glsl;
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_1);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_generate_debug_info(options);
            long pp = invoke(compiler, GlslAdapter.prepare(expanded), stage, name, options, true);
            String preprocessed;
            try {
                validate(pp, name + " preprocess");
                preprocessed = MemoryUtil.memUTF8(shaderc_result_get_bytes(pp));
            } finally { if (pp != 0) shaderc_result_release(pp); }
            if(scene && !java.util.regex.Pattern.compile("\\bvoid\\s+main\\s*\\(").matcher(preprocessed).find()) return null;
            glsl = scene?SceneAdapter.adapt(preprocessed,stage):GlslAdapter.adapt(preprocessed, vertex);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            long result = invoke(compiler, glsl, stage, name, options, false);
            try {
                validate(result, name + " compile");
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                spv = new byte[bytes.remaining()];
                bytes.get(spv);
            } finally { if (result != 0) shaderc_result_release(result); }
        } finally {
            if (options != 0) shaderc_compile_options_release(options);
            if (compiler != 0) shaderc_compiler_release(compiler);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer out = stack.callocPointer(1);
            check(0, spvc_context_create(out));
            long context = out.get(0);
            ByteBuffer bytes = MemoryUtil.memAlloc(spv.length);
            try {
                bytes.put(spv).flip();
                check(context, spvc_context_parse_spirv(context, bytes.asIntBuffer(), spv.length / 4, out));
                long ir = out.get(0);
                check(context, spvc_context_create_compiler(context, SPVC_BACKEND_MSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, out));
                long cross = out.get(0);
                check(context, spvc_compiler_create_compiler_options(cross, out));
                long settings = out.get(0);
                check(context, spvc_compiler_options_set_uint(settings, SPVC_COMPILER_OPTION_MSL_VERSION, 30000));
                check(context, spvc_compiler_options_set_uint(settings, SPVC_COMPILER_OPTION_MSL_PLATFORM, SPVC_MSL_PLATFORM_MACOS));
                if (vertex) check(context, spvc_compiler_options_set_bool(settings, SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true));
                if (vertex && scene) check(context, spvc_compiler_options_set_bool(settings, SPVC_COMPILER_OPTION_FIXUP_DEPTH_CONVENTION, true));
                check(context, spvc_compiler_install_compiler_options(cross, settings));
                check(context, spvc_compiler_create_shader_resources(cross, out));
                long resources = out.get(0);
                int model = vertex ? SpvExecutionModelVertex : stage==shaderc_compute_shader?SpvExecutionModelGLCompute:SpvExecutionModelFragment;
                List<Map<String, Object>> buffers = new ArrayList<>(), textures = new ArrayList<>();
                PointerBuffer list = stack.callocPointer(1), count = stack.callocPointer(1);
                if (vertex && !scene) {
                    check(context, spvc_resources_get_resource_list_for_type(resources, SPVC_RESOURCE_TYPE_STAGE_INPUT, list, count));
                    for (int i = 0; i < count.get(0); i++) {
                        var r = SpvcReflectedResource.create(list.get(0) + i * SpvcReflectedResource.SIZEOF);
                        if (spvc_compiler_get_decoration(cross, r.id(), SpvDecorationLocation) > 1)
                            throw new IllegalArgumentException("Final-pass probe cannot supply active vertex input: " + r.nameString());
                    }
                }
                check(context, spvc_resources_get_resource_list_for_type(resources, SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, list, count));
                for (int i = 0; i < count.get(0); i++) {
                    SpvcReflectedResource r = SpvcReflectedResource.create(list.get(0) + i * SpvcReflectedResource.SIZEOF);
                    int binding = spvc_compiler_get_decoration(cross, r.id(), SpvDecorationBinding);
                    bind(stack, context, cross, model, binding, i, 0, 0);
                    long type = spvc_compiler_get_type_handle(cross, r.base_type_id());
                    PointerBuffer size = stack.callocPointer(1);
                    check(context, spvc_compiler_get_declared_struct_size(cross, type, size));
                    Map<String, Integer> offsets = new LinkedHashMap<>();
                    for (int m = 0; m < spvc_type_get_num_member_types(type); m++) {
                        var offset = stack.callocInt(1);
                        check(context, spvc_compiler_type_struct_member_offset(cross, type, m, offset));
                        offsets.put(spvc_compiler_get_member_name(cross, r.base_type_id(), m), offset.get(0));
                    }
                    buffers.add(Map.of("name", r.nameString(), "slot", i, "size", size.get(0), "offsets", offsets));
                }
                check(context, spvc_resources_get_resource_list_for_type(resources, SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, list, count));
                for (int i = 0; i < count.get(0); i++) {
                    SpvcReflectedResource r = SpvcReflectedResource.create(list.get(0) + i * SpvcReflectedResource.SIZEOF);
                    int binding = spvc_compiler_get_decoration(cross, r.id(), SpvDecorationBinding);
                    bind(stack, context, cross, model, binding, 0, i, i);
                    textures.add(Map.of("name", r.nameString(), "slot", i));
                }
                Map<String,Object> interfaces=new LinkedHashMap<>();
                for(int category:new int[]{SPVC_RESOURCE_TYPE_STAGE_INPUT,SPVC_RESOURCE_TYPE_STAGE_OUTPUT,SPVC_RESOURCE_TYPE_STORAGE_IMAGE,SPVC_RESOURCE_TYPE_STORAGE_BUFFER}) {
                    check(context,spvc_resources_get_resource_list_for_type(resources,category,list,count));
                    List<Map<String,Object>> entries=new ArrayList<>();
                    for(int i=0;i<count.get(0);i++) {
                        var r=SpvcReflectedResource.create(list.get(0)+i*SpvcReflectedResource.SIZEOF);
                        long type=spvc_compiler_get_type_handle(cross,r.type_id());
                        entries.add(Map.of("name",r.nameString(),"location",spvc_compiler_get_decoration(cross,r.id(),SpvDecorationLocation),
                            "set",spvc_compiler_get_decoration(cross,r.id(),SpvDecorationDescriptorSet),"binding",spvc_compiler_get_decoration(cross,r.id(),SpvDecorationBinding),
                            "baseType",spvc_type_get_basetype(type),"vectorSize",spvc_type_get_vector_size(type),"columns",spvc_type_get_columns(type)));
                    }
                    interfaces.put(switch(category) {case SPVC_RESOURCE_TYPE_STAGE_INPUT -> "inputs";case SPVC_RESOURCE_TYPE_STAGE_OUTPUT -> "outputs";case SPVC_RESOURCE_TYPE_STORAGE_IMAGE -> "storageImages";default -> "storageBuffers";},entries);
                }
                check(context, spvc_compiler_compile(cross, out));
                String metal = MemoryUtil.memUTF8(out.get(0));
                String entry = spvc_compiler_get_cleansed_entry_point_name(cross, "main", model);
                return new Compiled(glsl, spv, metal, entry, buffers, textures,interfaces);
            } finally {
                MemoryUtil.memFree(bytes);
                spvc_context_destroy(context);
            }
        }
    }

    private static void bind(MemoryStack stack, long context, long cross, int stage, int binding, int buffer, int texture, int sampler) {
        var b = SpvcMslResourceBinding.calloc(stack).stage(stage).desc_set(0).binding(binding).msl_buffer(buffer).msl_texture(texture).msl_sampler(sampler);
        check(context, spvc_compiler_msl_add_resource_binding(cross, b));
    }

    private static long invoke(long compiler, String source, int stage, String name, long options, boolean preprocess) {
        // Expanded packs exceed LWJGL's small per-thread MemoryStack. Keep source bytes on the native heap.
        ByteBuffer text = MemoryUtil.memUTF8(source, false), file = MemoryUtil.memUTF8(name), entry = MemoryUtil.memUTF8("main");
        try {
            return preprocess ? shaderc_compile_into_preprocessed_text(compiler, text, stage, file, entry, options)
                : shaderc_compile_into_spv(compiler, text, stage, file, entry, options);
        } finally {
            MemoryUtil.memFree(text);
            MemoryUtil.memFree(file);
            MemoryUtil.memFree(entry);
        }
    }

    private static void validate(long result, String label) {
        if (result == 0) throw new IllegalStateException(label + ": no compiler result");
        if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
            throw new IllegalArgumentException(label + ": " + shaderc_result_get_error_message(result));
    }

    private static void check(long context, int result) {
        if (result != SPVC_SUCCESS) throw new IllegalStateException("SPIRV-Cross " + result + ": " + (context == 0 ? "initialization" : spvc_context_get_last_error_string(context)));
    }
}
