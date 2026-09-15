package com.example.golem_covenant.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import com.example.golem_covenant.client.CameraShake;

/**
 * Applies the ritual screen shake to the camera (spec 13.1).
 *
 * <p>The injection point is {@code extractRenderState}, which is where the
 * camera copies its own orientation into the {@link CameraRenderState} the
 * renderer consumes. Perturbing the render state - rather than the camera's
 * own rotation - means the shake is purely a rendering artefact: it cannot
 * leak into the player's actual look direction, cannot be sent to the server,
 * and cannot desync the client from its own position. That matters, because
 * spec 13.1 makes this a cosmetic that a player can turn off, and a cosmetic
 * that mutates real state is a bug waiting to happen.
 *
 * <p>Applying the offset via the two public float fields (rather than trying
 * to rebuild the quaternion) keeps the mixin tiny and robust: if the camera's
 * internal representation changes, this still compiles and still reads as a
 * shake, it simply does not disturb the orientation quaternion.
 */
@Mixin(Camera.class)
public class CameraShakeMixin {

	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void golemCovenant$applyRitualShake(CameraRenderState state,
			float partialTick, CallbackInfo ci) {
		if (!CameraShake.active() || state == null) {
			return;
		}
		float roll = CameraShake.rollDegrees(partialTick);
		float pitch = CameraShake.pitchDegrees(partialTick);
		if (roll == 0.0f && pitch == 0.0f) {
			return;
		}
		// Only the render-state rotation is touched (see class doc).
		state.xRot += pitch;
		state.yRot += roll;
	}
}
