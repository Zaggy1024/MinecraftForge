package net.minecraftforge.client.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.vecmath.Matrix4f;

import net.minecraft.client.renderer.block.model.ModelBlockDefinition;
import net.minecraft.client.resources.model.ModelRotation;
import net.minecraft.util.ResourceLocation;

import org.apache.commons.io.Charsets;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@SuppressWarnings("deprecation")
public class BlockStateLoader
{
    private static final Gson GSON = (new GsonBuilder())
            .registerTypeAdapter(ForgeBlockStateV1.class,         ForgeBlockStateV1.Deserializer.INSTANCE)
            .registerTypeAdapter(ForgeBlockStateV1.Variant.class, ForgeBlockStateV1.Variant.Deserializer.INSTANCE)
            .create();
    /**
     * Loads a BlockStates json file.
     * Will attempt to parse it as a Forge Enhanced version if possible.
     * Will fall back to standard loading if marker is not present.
     *
     * Note: This method is NOT thread safe
     *
     * @param reader json read
     * @param vanillaGSON ModelBlockDefinition's GSON reader.
     *
     * @return Model definition including variants for all known combinations.
     */
    @SuppressWarnings("rawtypes")
    public static ModelBlockDefinition load(Reader reader, final Gson vanillaGSON)
    {
        try
        {
            byte[] data = IOUtils.toByteArray(reader);
            reader = new InputStreamReader(new ByteArrayInputStream(data), Charsets.UTF_8);

            Marker marker = GSON.fromJson(new String(data), Marker.class);  // Read "forge_marker" to determine what to load.

            switch (marker.forge_marker)
            {
                case 1: // Version 1
                    ForgeBlockStateV1 v1 = GSON.fromJson(reader, ForgeBlockStateV1.class);
                    List<ModelBlockDefinition.Variants> variants = Lists.newArrayList();

                    for (Entry<String, Collection<ForgeBlockStateV1.Variant>> entry : v1.variants.asMap().entrySet())
                    {   // Convert Version1 variants into vanilla variants for the ModelBlockDefinition.
                        List<ModelBlockDefinition.Variant> mcVars = Lists.newArrayList();
                        for (ForgeBlockStateV1.Variant var : entry.getValue())
                        {
                            ModelRotation rot = var.rotation.or(ModelRotation.X0_Y0);
                            boolean uvLock = var.uvLock.or(false);
                            int weight = var.weight.or(1);

                            if (var.model != null && var.submodels.size() == 0 && var.textures.size() == 0)
                                mcVars.add(new ModelBlockDefinition.Variant(var.model, rot, uvLock, weight));
                            else
                                mcVars.add(new ForgeVariant(var.model, rot, uvLock, weight, var.textures, var.getOnlyPartsVariant()));
                        }
                        variants.add(new ModelBlockDefinition.Variants(entry.getKey(), mcVars));
                    }

                    return new ModelBlockDefinition((Collection)variants); //Damn lists being collections!

                default: //Unknown version.. try loading it as normal.
                    return vanillaGSON.fromJson(reader, ModelBlockDefinition.class);
            }
        }
        catch (IOException e)
        {
            Throwables.propagate(e);
        }
        return null;
    }

    public static class Marker
    {
        public int forge_marker = -1;
    }

    //This is here specifically so that we do not have a hard reference to ForgeBlockStateV1.Variant in ForgeVariant
    public static interface ISubModel
    {
        public ModelRotation getRotation();
        public boolean isUVLock();
        public Map<String, String> getTextures();
        public ResourceLocation getModelLocation();

        public static class Impl implements ISubModel
        {
            final ModelRotation rotation;
            final boolean uvLock;
            final Map<String, String> textures;
            final ResourceLocation model;

            public Impl(ModelRotation rotation, boolean uvLock, Map<String, String> textures, ResourceLocation model)
            {
                this.rotation = rotation;
                this.uvLock = uvLock;
                this.textures = textures;
                this.model = model;
            }
            @Override public ModelRotation getRotation() { return rotation; }
            @Override public boolean isUVLock() { return uvLock; }
            @Override public Map<String, String> getTextures() { return textures; }
            @Override public ResourceLocation getModelLocation() { return model; }
        }
    }

    private static class ForgeVariant extends ModelBlockDefinition.Variant implements ISmartVariant
    {
        Map<String, String> textures;
        Map<String, ISubModel> parts;

        public ForgeVariant(ResourceLocation model, ModelRotation rotation, boolean uvLock, int weight,
                Map<String, String> textures, Map<String, ISubModel> parts)
        {
            super(model == null ? new ResourceLocation("builtin/missing") : model, rotation, uvLock, weight);
            this.textures = textures;
            this.parts = parts;
        }

        /**
         * @return A new IModel retextured using the map of texture name -> texture path.
         */
        protected IModel retexture(IModel base, Map<String, String> textureMap)
        {
            if (textureMap.isEmpty())
                return base;
            else if (base instanceof IRetexturableModel)
                return ((IRetexturableModel)base).retexture(textureMap);
            else
                throw new RuntimeException("Attempted to retexture a non-retexturable model: " + base);
        }

        /**
         * Used to replace the base model with a retextured model containing submodels.
         */
        @Override
        public IModel process(IModel base, ModelLoader loader)
        {
            int size = parts.size();
            boolean hasBase = base != loader.getMissingModel();

            if (hasBase)
            {
                base = retexture(base, textures);

                if (size <= 0)
                    return base;
            }

            // Apply rotation of base model to submodels.
            // If baseRot is non-null, then that rotation will be applied instead of the base model's rotation.
            // This is used to allow replacing base model with a submodel when there is no base model for a variant.
            ModelRotation baseRot = getRotation();
            baseRot = getRotation();
            ImmutableMap.Builder<String, Pair<IModel, IModelState>> models = ImmutableMap.builder();
            for (Entry<String, ISubModel> entry : parts.entrySet())
            {
                ISubModel part = entry.getValue();

                Matrix4f matrix = new Matrix4f(baseRot.getMatrix());
                matrix.mul(part.getRotation().getMatrix());
                IModelState partState = new TRSRTransformation(matrix);
                if (part.isUVLock()) partState = new ModelLoader.UVLock(partState);

                models.put(entry.getKey(), Pair.of(retexture(loader.getModel(part.getModelLocation()), part.getTextures()), partState));
            }

            return new MultiModel(hasBase ? base : null, models.build());
        }

        @Override
        public String toString()
        {
            StringBuilder buf = new StringBuilder();
            buf.append("TexturedVariant:");
            for (Entry<String, String> e: this.textures.entrySet())
                buf.append(" ").append(e.getKey()).append(" = ").append(e.getValue());
            return buf.toString();
        }
    }
}
