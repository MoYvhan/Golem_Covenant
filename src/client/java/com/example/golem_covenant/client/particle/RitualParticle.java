package com.example.golem_covenant.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.RandomSource;

import com.example.golem_covenant.client.RitualParticles;

/**
 * The single particle implementation behind every golem-covenant effect
 * (spec 5.7 / 12.4).
 *
 * <p>One class covers all twelve registered types. Behaviour differences come
 * from {@link RitualParticles.Style} rather than from twelve near-identical
 * subclasses - the same data-driven principle the rest of the mod follows
 * (spec 12.1): the form data decides the look, not the class hierarchy.
 *
 * <p>Every particle is a client-side quad. Spec 13.2.1 forbids using entities
 * as the effect carrier, so nothing here ever spawns an entity or a block.
 */
public class RitualParticle extends SingleQuadParticle {

	/** Per-tick vertical drift applied on top of the spawn velocity. */
	private final float rise;
	/** Per-tick shrink factor; 1.0 means the sprite never shrinks. */
	private final float shrink;
	/** Non-zero makes the sprite spin about its own axis. */
	private final float rollSpeed;
	/** Gravity is negative here for motes that float up and fade. */
	private final float buoyancy;

	private final SpriteSet sprites;

	private RitualParticle(ClientLevel level, double x, double y, double z,
			double dx, double dy, double dz, SpriteSet sprites,
			RitualParticles.Style style, RandomSource random) {
		super(level, x, y, z, dx, dy, dz, sprites.first());
		this.sprites = sprites;
		this.xd = dx;
		this.yd = dy;
		this.zd = dz;

		// Spec 13.2.3: every particle is short-lived and small, so a full
		// ritual circle never becomes a wall of colour in first-person view.
		this.lifetime = style.lifeMin()
				+ random.nextInt(Math.max(1, style.lifeMax() - style.lifeMin()));
		this.quadSize = style.size() * (0.7f + random.nextFloat() * 0.6f);
		this.rise = style.rise();
		this.shrink = style.shrink();
		this.rollSpeed = style.roll();
		this.buoyancy = style.buoyancy();
		this.gravity = style.gravity();
		this.friction = style.friction();
		this.hasPhysics = style.hasPhysics();

		// Tinting. Untinted particles keep their vanilla atlas colour; tinted
		// ones are multiplied by the style colour, which is where the ten
		// families become visually distinct (spec 5.3).
		if (style.tint()) {
			float jitter = 0.85f + random.nextFloat() * 0.3f;
			this.rCol = clamp01(style.red() * jitter);
			this.gCol = clamp01(style.green() * jitter);
			this.bCol = clamp01(style.blue() * jitter);
		}

		this.roll = random.nextFloat() * (float) (Math.PI * 2.0);
		this.oRoll = this.roll;

		// Advance the sprite once so the first visible frame is not always
		// frame 0 - otherwise a ring of particles looks like a ring of clones.
		this.setSpriteFromAge(sprites);
	}

	private static float clamp01(float value) {
		return value < 0.0f ? 0.0f : Math.min(value, 1.0f);
	}

	@Override
	public void tick() {
		super.tick();

		if (this.removed) {
			return;
		}

		// Vertical character: floating motes rise, sparks fall, dust hangs.
		this.yd += this.rise + this.buoyancy;

		if (this.shrink < 1.0f) {
			this.quadSize *= this.shrink;
			if (this.quadSize < 0.01f) {
				this.remove();
				return;
			}
		}

		if (this.rollSpeed != 0.0f) {
			this.oRoll = this.roll;
			this.roll += this.rollSpeed;
		}

		// Fade out over the last third of the life so nothing pops away
		// abruptly in the middle of a ceremony.
		int remaining = this.lifetime - this.age;
		if (remaining < this.lifetime / 3) {
			this.alpha = Math.max(0.05f, remaining / (float) (this.lifetime / 3));
		}

		this.setSpriteFromAge(this.sprites);
	}

	@Override
	protected Layer getLayer() {
		return Layer.TRANSLUCENT;
	}

	@Override
	public ParticleRenderType getGroup() {
		return ParticleRenderType.SINGLE_QUADS;
	}

	/**
	 * Provider factory for one {@link RitualParticles.Style}.
	 *
	 * <p>Registered with Fabric's {@code ParticleProviderRegistry} so the
	 * client resolves the particle to the sprite set named in
	 * {@code assets/golem_covenant/particles/<name>.json}.
	 */
	public record Provider(SpriteSet sprites, RitualParticles.Style style)
			implements net.minecraft.client.particle.ParticleProvider<net.minecraft.core.particles.SimpleParticleType> {

		@Override
		public Particle createParticle(
				net.minecraft.core.particles.SimpleParticleType type,
				ClientLevel level, double x, double y, double z,
				double dx, double dy, double dz, RandomSource random) {
			return new RitualParticle(level, x, y, z, dx, dy, dz, sprites,
					style, random);
		}
	}

	/** Exposed for the energy column, which stacks quads along one axis. */
	public static TextureAtlasSprite firstSprite(SpriteSet sprites) {
		return sprites.first();
	}
}
