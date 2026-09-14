package com.example.golem_covenant.registry;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;

import com.example.golem_covenant.GolemCovenantMod;
import com.example.golem_covenant.data.CovenantData;

/**
 * Fabric Data Attachments (spec 12.4).
 *
 * <p>Persisted + synced covenant state per entity. {@code persistent} makes it
 * survive save/load (spec 11.10.3 server restart), {@code copyOnDeath} keeps a
 * companion's record across a death so the will system can observe it.
 */
public final class ModAttachments {

	private ModAttachments() {
	}

	public static final AttachmentType<CovenantData> COVENANT =
			AttachmentRegistry.<CovenantData>builder()
					.persistent(CovenantData.CODEC)
					.copyOnDeath()
					.initializer(() -> CovenantData.empty())
					.buildAndRegister(GolemCovenantMod.id("covenant"));

	public static void register() {
		// class-init triggers the static registration above
		GolemCovenantMod.LOGGER.debug("attachment registered: {}",
				Identifier.fromNamespaceAndPath(GolemCovenantMod.MOD_ID, "covenant"));
	}
}
