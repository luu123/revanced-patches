package app.revanced.patches.youtube.misc.backgroundplayback

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patches.youtube.misc.backgroundplayback.fingerprints.BackgroundPlaybackManagerFingerprint
import app.revanced.patches.youtube.misc.backgroundplayback.fingerprints.BackgroundPlaybackSettingsFingerprint
import app.revanced.patches.youtube.misc.backgroundplayback.fingerprints.KidsBackgroundPlaybackPolicyControllerFingerprint
import app.revanced.patches.youtube.misc.backgroundplayback.fingerprints.KidsBackgroundPlaybackPolicyControllerParentFingerprint
import app.revanced.patches.youtube.misc.backgroundplayback.fingerprints.PiPControllerFingerprint
import app.revanced.patches.youtube.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.revanced.patches.youtube.utils.integrations.Constants.MISC_PATH
import app.revanced.patches.youtube.utils.playertype.PlayerTypeHookPatch
import app.revanced.patches.youtube.utils.settings.SettingsPatch
import app.revanced.patches.youtube.video.information.VideoInformationPatch
import app.revanced.util.findOpcodeIndicesReversed
import app.revanced.util.getWalkerMethod
import app.revanced.util.patch.BaseBytecodePatch
import app.revanced.util.resultOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
object BackgroundPlaybackPatch : BaseBytecodePatch(
    name = "Remove background playback restrictions",
    description = "Removes restrictions on background playback, including for music and kids videos.",
    dependencies = setOf(
        PlayerTypeHookPatch::class,
        VideoInformationPatch::class,
        SettingsPatch::class
    ),
    compatiblePackages = COMPATIBLE_PACKAGE,
    fingerprints = setOf(
        BackgroundPlaybackManagerFingerprint,
        BackgroundPlaybackSettingsFingerprint,
        KidsBackgroundPlaybackPolicyControllerParentFingerprint,
        PiPControllerFingerprint
    )
) {
    override fun execute(context: BytecodeContext) {

        BackgroundPlaybackManagerFingerprint.resultOrThrow().mutableMethod.apply {
            findOpcodeIndicesReversed(Opcode.RETURN).forEach{ index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                // Replace to preserve control flow label.
                replaceInstruction(
                    index,
                    "invoke-static { v$register }, $MISC_PATH/BackgroundPlaybackPatch;->allowBackgroundPlayback(Z)Z"
                )

                addInstructions(index + 1,
                    """
                       move-result v$register
                       return v$register
                    """
                )
            }
        }

        // Enable background playback option in YouTube settings
        BackgroundPlaybackSettingsFingerprint.resultOrThrow().let {
            it.mutableMethod.apply {
                val booleanCalls = implementation!!.instructions.withIndex()
                    .filter { instruction ->
                        ((instruction.value as? ReferenceInstruction)?.reference as? MethodReference)?.returnType == "Z"
                    }

                val booleanIndex = booleanCalls.elementAt(1).index
                val booleanMethod = getWalkerMethod(context, booleanIndex)

                booleanMethod.addInstructions(
                    0, """
                        const/4 v0, 0x1
                        return v0
                        """
                )
            }
        }

        // Force allowing background play for videos labeled for kids.
        KidsBackgroundPlaybackPolicyControllerFingerprint.resolve(
            context,
            KidsBackgroundPlaybackPolicyControllerParentFingerprint.resultOrThrow().classDef
        )
        KidsBackgroundPlaybackPolicyControllerFingerprint.resultOrThrow().mutableMethod.addInstruction(
            0,
            "return-void"
        )

        PiPControllerFingerprint.resultOrThrow().let {
            val targetMethod =
                it.getWalkerMethod(context, it.scanResult.patternScanResult!!.endIndex)

            targetMethod.apply {
                val targetRegister = getInstruction<TwoRegisterInstruction>(0).registerA

                addInstruction(
                    1,
                    "const/4 v$targetRegister, 0x1"
                )
            }
        }

        SettingsPatch.updatePatchStatus(this)
    }
}
