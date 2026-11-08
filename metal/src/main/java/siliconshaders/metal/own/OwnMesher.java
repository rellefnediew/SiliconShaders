package siliconshaders.metal.own;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.jspecify.annotations.Nullable;

/**
 * Our own mesher (-Dsiliconshaders.own.mesh=true, needs -Dsiliconshaders.own.compact): vanilla's SectionCompiler.compile restated, with the solid
 * and cutout quads going into OwnQuads (compact vertices, facing buckets) instead of BufferBuilders. Vanilla's
 * ModelBlockRenderer and FluidRenderer still compute every quad, its AO, light and tint, so the picture is vanilla's.
 * Translucent stays on vanilla's BufferBuilder (and its sort). Results carries the OwnQuads (OwnQuadHolder) to the upload
 * hook; each own layer appears in renderedLayers as a 4-byte placeholder MeshData whose draw state has the layer's real
 * counts, so vanilla's task flow (section draws, upload callbacks, mesh swap) is unchanged.
 */
public final class OwnMesher {
	public static final String MODE = System.getProperty("siliconshaders.own.mesh", "false");
	public static final boolean ON = "true".equals(MODE) && OwnTerrain.COMPACT && (!"native".equals(System.getProperty("siliconshaders.shade")));
	/** Vanilla meshes and stores as usual; our mesher runs beside it on the same region and every layer is compared. */
	public static final boolean VERIFY = "verify".equals(MODE);
	/** -Dsiliconshaders.own.mesh.region=true: vanilla's renderers read the neighbourhood through OwnRegion's per-compile caches. */
	static final boolean REGION = Boolean.getBoolean("siliconshaders.own.mesh.region");
	private static final long[] HIDDEN_TOTAL = new long[2], HIDDEN_COUNT = new long[2 * 7];
	private static long hiddenLog, hiddenControl;


	static {
		if (("true".equals(MODE) || VERIFY) && !OwnTerrain.COMPACT) siliconshaders.metal.Log.info("siliconshaders-own mesh: needs -Dsiliconshaders.own.compact=true, off");
		if (ON || VERIFY) siliconshaders.metal.Log.info("siliconshaders-own mesh: " + MODE);
	}

	private OwnMesher() {
	}

	/** Vanilla's compile, our outputs for solid and cutout. Null when our terrain isn't up (vanilla's compile runs then). */
	public static SectionCompiler.@Nullable Results compile(boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModelSet,
		FluidStateModelSet fluidModelSet, BlockColors blockColors, SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
		SectionBufferBuilderPack builders) {
		if (OwnTerrain.get() == null) return null;
		SectionCompiler.Results results = new SectionCompiler.Results();
		OwnQuads quads = OwnQuads.get();
		Map<ChunkSectionLayer, BufferBuilder> startedLayers = new EnumMap<>(ChunkSectionLayer.class);
		// (OwnIntCompilerMixin brackets vanilla's compile with this; our HEAD hook may cancel before it runs)
		
		try {
			mesh(ambientOcclusion, cutoutLeaves, blockModelSet, fluidModelSet, blockColors, sectionPos, region, builders, results, quads, startedLayers);
		} finally {
			// cleared, not restored: vanilla's compile is cancelled, so OwnIntCompilerMixin's RETURN hook won't clear it
			
		}
		for (Map.Entry<ChunkSectionLayer, BufferBuilder> entry : startedLayers.entrySet()) {
			ChunkSectionLayer layer = entry.getKey();
			MeshData mesh = entry.getValue().build();
			if (mesh != null) {
				if (layer == ChunkSectionLayer.TRANSLUCENT) results.transparencyState = mesh.sortQuads(builders.buffer(layer), vertexSorting);
				results.renderedLayers.put(layer, mesh);
			}
		}
		for (int l = 0; l < 2; l++) {
			int n = quads.layers[l].total();
			if (n == 0) continue;
			ChunkSectionLayer layer = l == 0 ? ChunkSectionLayer.SOLID : ChunkSectionLayer.CUTOUT;
			ByteBufferBuilder buffer = builders.buffer(layer);
			buffer.reserve(4);
			ByteBufferBuilder.Result placeholder = buffer.build();
			results.renderedLayers.put(layer, new MeshData(placeholder,
				new MeshData.DrawState(layer.vertexFormat(), n * 4, n * 6, PrimitiveTopology.QUADS, IndexType.least(n * 4))));
		}
		// (the removed flag own.mesh.tieGroups: the identical-corner groups, here on the worker, from the final quads)
		if (OwnTieGroups.ON) quads.groups = OwnTieGroups.build(quads, OwnTerrain.RUN);
		((OwnQuadHolder) (Object) results).siliconshaders$setQuads(quads);
		
		if (OwnMergeStat.ON) OwnMergeStat.count(quads);
		return results;
	}

	/**
	 * The block loop of vanilla's compile. With startedLayers null (verify), translucent quads are dropped and nothing else of
	 * results is touched but what vanilla's loop touches (block entities, visibility).
	 */
	static void mesh(boolean ambientOcclusion, boolean cutoutLeaves, BlockStateModelSet blockModelSet, FluidStateModelSet fluidModelSet,
		BlockColors blockColors, SectionPos sectionPos, RenderSectionRegion region, SectionBufferBuilderPack builders, SectionCompiler.Results results,
		OwnQuads quads, @Nullable Map<ChunkSectionLayer, BufferBuilder> startedLayers) {
        if(siliconshaders.metal.sun.SunLighting.enabled()) ambientOcclusion=false;
		BlockPos minPos = sectionPos.origin();
		VisGraph visGraph = (new VisGraph());
		OwnVisGraph ownVis = (null);
		BlockModelLighter.enableCaching();
		ModelBlockRenderer blockRenderer = new ModelBlockRenderer(ambientOcclusion, true, blockColors);
		FluidRenderer fluidRenderer = new FluidRenderer(fluidModelSet);
		OwnRegion own = REGION ? OwnRegion.of(region, minPos) : null;
		BlockAndTintGetter level = own != null ? own : region;
		OwnBlockRenderer ownRenderer = own != null && OwnBlockRenderer.ON ? OwnBlockRenderer.get(ambientOcclusion, blockColors) : null;
		if (ownRenderer != null) ownRenderer.qrecSink = OwnQrec.ON ? quads : null;
		VertexConsumer[] translucent = new VertexConsumer[1];
		BlockQuadOutput quadOutput = (x, y, z, quad, instance) -> {
            if(quads.glassBlock)quads.glass.put(x,y,z,quad,instance);
			ChunkSectionLayer layer = quad.materialInfo().layer();
			if (layer == ChunkSectionLayer.SOLID) quads.put(0, x, y, z, quad, instance);
			else if (layer == ChunkSectionLayer.CUTOUT) quads.put(1, x, y, z, quad, instance);
			else if (startedLayers != null) translucent(startedLayers, builders, translucent,quads).putBlockBakedQuad(x, y, z, quad, instance);
		};
		BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) -> quads.put(0, x, y, z, quad, instance);
		FluidRenderer.Output fluidOutput = layer -> layer == ChunkSectionLayer.SOLID ? quads.fluid[0]
			: layer == ChunkSectionLayer.CUTOUT ? quads.fluid[1]
			: startedLayers != null ? translucent(startedLayers, builders, translucent,quads) : Discard.INSTANCE;
		// With Fabric API's renderer API loaded, blocks go through its renderer as Fabric API's own compile patch does
		OwnApi.Mesher api = OwnApi.mesher(ambientOcclusion, blockColors, quads, () -> startedLayers != null ? translucent(startedLayers, builders, translucent,quads) : Discard.INSTANCE);
		// BlockPos.betweenClosed's order (x fastest, then y, then z) with its one mutable cursor, without the iterator
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = minPos.getX(), minY = minPos.getY(), minZ = minPos.getZ();
		for (int i = 0; i < 4096; i++) {
			pos.set(minX + (i & 15), minY + (i >> 4 & 15), minZ + (i >> 8));
			BlockState blockState = level.getBlockState(pos);
            quads.glassBlock=siliconshaders.metal.sun.SunGlassQuads.isGlass(blockState);
            quads.windBlock(blockState,pos.getY()&15);
			if (OwnGridStat.ON || OwnQrStat.ON) OwnGridStat.block(blockState, quads);
			  // as OwnIntCompilerMixin's redirect
			if (!blockState.isAir()) {
				try {
					if (blockState.isSolidRender()) {
						if (ownVis != null) ownVis.setOpaque(i & 15, i >> 4 & 15, i >> 8);
						else visGraph.setOpaque(pos);
					}
					if (blockState.hasBlockEntity() && startedLayers != null) {
						BlockEntity blockEntity = region.getBlockEntity(pos);
						if (blockEntity != null) results.blockEntities.add(blockEntity);
					}
					FluidState fluidState = blockState.getFluidState();
					if (!fluidState.isEmpty()) {
                        quads.waterFluid=fluidState.is(net.minecraft.tags.FluidTags.WATER)&&OwnTerrain.takes(ChunkSectionLayer.TRANSLUCENT);
                        try { fluidRenderer.tesselate(level,pos,fluidOutput,blockState,fluidState); }
                        finally { quads.waterFluid=false; }
                    }
					if (blockState.getRenderShape() == RenderShape.MODEL && api != null) {
						// vanilla's region, not OwnRegion: Fabric API extends RenderSectionRegion (block entity render data, biomes) for FRAPI models
						api.block(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), region, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					} else if (blockState.getRenderShape() == RenderShape.MODEL && ownRenderer != null) {
						ownRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
							SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), own, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					} else if (blockState.getRenderShape() == RenderShape.MODEL) {
						blockRenderer.tesselateBlock(ModelBlockRenderer.forceOpaque(cutoutLeaves, blockState) ? opaqueQuadOutput : quadOutput,
							SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getY()), SectionPos.sectionRelative(pos.getZ()), level, pos,
							blockState, blockModelSet.get(blockState), blockState.getSeed(pos));
					}
				} catch (Throwable t) {
					CrashReport report = CrashReport.forThrowable(t, "Tesselating block in world");
					CrashReportCategory category = report.addCategory("Block being tesselated");
					CrashReportCategory.populateBlockDetails(category, region, pos, blockState);
					throw new ReportedException(report);
				}
			}
		}
		BlockModelLighter.clearCache();
		if (own != null) own.release();
		// (verify reads it too: our visibility is compared with vanilla's)
		results.visibilitySet = ownVis != null ? ownVis.resolve() : visGraph.resolve();
	}

	private static VertexConsumer translucent(Map<ChunkSectionLayer, BufferBuilder> startedLayers, SectionBufferBuilderPack builders, VertexConsumer[] cache,OwnQuads quads) {
		VertexConsumer c = cache[0];
		if (c == null) {
			BufferBuilder builder = new BufferBuilder(builders.buffer(ChunkSectionLayer.TRANSLUCENT), PrimitiveTopology.QUADS, ChunkSectionLayer.TRANSLUCENT.vertexFormat());
			startedLayers.put(ChunkSectionLayer.TRANSLUCENT, builder);
			cache[0] = c = new WaterVertices(builder,()->quads.waterFluid,()->SurfaceMaterials.ON&&OwnTerrain.takes(ChunkSectionLayer.TRANSLUCENT)?quads.translucentMaterial:0);
		}
		return c;
	}

	/** Verify mode's sink for translucent fluid vertices (vanilla's own compile has them already). */
	private enum Discard implements VertexConsumer {
		INSTANCE;

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
