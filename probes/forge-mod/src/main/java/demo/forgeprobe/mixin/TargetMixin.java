package demo.forgeprobe.mixin;
import demo.forgeprobe.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Target.class)
public class TargetMixin {
    @Inject(method="value", at=@At("HEAD"), cancellable=true)
    private static void nf$value(CallbackInfoReturnable<Integer> result) { result.setReturnValue(2); }
}
