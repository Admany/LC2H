import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.util.shaderc.Shaderc.*;

public final class CompileSpirv {
    private CompileSpirv() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: CompileSpirv <input.comp> <output.spv>");
        }
        Path sourcePath = Path.of(args[0]);
        Path outputPath = Path.of(args[1]);
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        if (compiler == MemoryUtil.NULL || options == MemoryUtil.NULL) {
            throw new IllegalStateException("Shaderc initialization failed");
        }
        long result = MemoryUtil.NULL;
        try {
            result = shaderc_compile_into_spv(compiler, Files.readString(sourcePath), shaderc_compute_shader,
                sourcePath.getFileName().toString(), "main", options);
            if (result == MemoryUtil.NULL) {
                throw new IllegalStateException("Shaderc returned no result");
            }
            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalStateException(shaderc_result_get_error_message(result));
            }
            ByteBuffer bytes = shaderc_result_get_bytes(result);
            byte[] output = new byte[bytes.remaining()];
            bytes.get(output);
            Files.createDirectories(outputPath.getParent());
            Files.write(outputPath, output);
            System.out.println("Compiled " + sourcePath + " -> " + outputPath + " (" + output.length + " bytes)");
        } finally {
            if (result != MemoryUtil.NULL) {
                shaderc_result_release(result);
            }
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
