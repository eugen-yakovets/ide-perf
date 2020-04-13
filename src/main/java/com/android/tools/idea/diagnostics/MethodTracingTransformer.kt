package com.android.tools.idea.diagnostics

import com.android.tools.idea.diagnostics.agent.Trampoline
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.org.objectweb.asm.*
import org.jetbrains.org.objectweb.asm.ClassReader.SKIP_FRAMES
import org.jetbrains.org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.jetbrains.org.objectweb.asm.Opcodes.ASM5
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.org.objectweb.asm.commons.Method
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import kotlin.reflect.jvm.javaMethod

// Things to improve:
// - Should be possible to do without COMPUTE_FRAMES (but then also remove SKIP_FRAMES.)
// - Reconsider which ASM API to use (does it matter?).

/** Inserts calls (within JVM byte code) to the trampoline. */
class MethodTracingTransformer(private val methodFilter: MethodFilter) : ClassFileTransformer {

    companion object {
        private val LOG = Logger.getInstance(MethodTracingTransformer::class.java)
        private const val ASM_API = ASM5

        private val TRAMPOLINE_CLASS_NAME: String = Type.getType(Trampoline::class.java).internalName
        private val TRAMPOLINE_ENTER_METHOD: Method = Method.getMethod(Trampoline::enter.javaMethod)
        private val TRAMPOLINE_LEAVE_METHOD: Method = Method.getMethod(Trampoline::leave.javaMethod)
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray? {
        return try {
            if (!methodFilter.shouldInstrumentClass(className)) return null
            tryTransform(className, classfileBuffer)
        }
        catch (e: Throwable) {
            LOG.warn("Failed to instrument class $className", e)
            throw e
        }
    }

    private fun tryTransform(
        className: String,
        classBytes: ByteArray
    ): ByteArray? {
        val reader = ClassReader(classBytes)
        val writer = ClassWriter(reader, COMPUTE_FRAMES)

        LOG.info("Instrumenting class: $className")
        val classVisitor = object : ClassVisitor(ASM_API, writer) {

            override fun visitMethod(
                access: Int,
                method: String,
                desc: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                val methodWriter = cv.visitMethod(access, method, desc, signature, exceptions)
                val id = methodFilter.getMethodId(className, method) ?: return methodWriter

                LOG.info("Instrumenting method: $className#$method")
                return object : AdviceAdapter(ASM_API, methodWriter, access, method, desc) {
                    val methodStart = Label()
                    val methodEnd = Label()

                    override fun onMethodEnter() {
                        invokeHook(TRAMPOLINE_ENTER_METHOD)
                        mv.visitLabel(methodStart)
                    }

                    override fun onMethodExit(opcode: Int) {
                        if (opcode != ATHROW) {
                            invokeHook(TRAMPOLINE_LEAVE_METHOD)
                        }
                    }

                    override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                        // visitMaxs is the first method called after visiting all instructions.
                        mv.visitLabel(methodEnd)

                        // We wrap the method in a try-catch block in order to
                        // invoke the exit hook even when an exception is thrown.
                        //
                        // The ASM library claims to require that visitTryCatchBlock be called
                        // *before* its arguments are visited, but doing that would place the
                        // handler at the beginning of the exception table, thus incorrectly
                        // taking priority over user catch blocks. Fortunately, violating the
                        // ASM requirement seems to work, despite breaking the ASM verifier...
                        mv.visitTryCatchBlock(methodStart, methodEnd, methodEnd, null)
                        invokeHook(TRAMPOLINE_LEAVE_METHOD)
                        mv.visitInsn(ATHROW) // Rethrow.

                        mv.visitMaxs(maxStack, maxLocals)
                    }

                    fun invokeHook(hook: Method) {
                        mv.visitLdcInsn(id)
                        mv.visitMethodInsn(
                            INVOKESTATIC,
                            TRAMPOLINE_CLASS_NAME,
                            hook.name,
                            hook.descriptor,
                            false
                        )
                    }
                }
            }
        }

        reader.accept(classVisitor, SKIP_FRAMES)
        return writer.toByteArray()
    }
}