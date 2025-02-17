/*
 * Copyright (C) 2010-2022 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.model.impl.lens.projector.focus.inbounds.prep;

import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.schema.processor.*;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.evolveum.midpoint.model.api.identities.IdentityItemConfiguration;
import com.evolveum.midpoint.model.common.mapping.MappingImpl;
import com.evolveum.midpoint.model.impl.lens.projector.focus.inbounds.InboundSourceData;
import com.evolveum.midpoint.model.impl.lens.projector.focus.inbounds.MappingEvaluationRequest;
import com.evolveum.midpoint.model.impl.lens.projector.focus.inbounds.StopProcessingProjectionException;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.PrismValue;
import com.evolveum.midpoint.prism.delta.ContainerDelta;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.repo.common.expression.Source;
import com.evolveum.midpoint.schema.config.InboundMappingConfigItem;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.util.DebugDumpable;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

/**
 * Source for the whole inbounds processing.
 *
 * Exists mainly to provide a common interface for the full a.k.a. clockwork-based processing ({@link FullInboundsSource})
 * and all the exceptional cases (like inbounds for correlation - {@link LimitedInboundsSource}).
 *
 * Contains the {@link InboundSourceData} being processed plus the necessary surroundings,
 * like inbound mapping beans, lens/projection context (in the case of clockwork processing), etc.
 *
 * There are a lot of abstract methods here, dealing with e.g. determining if the full shadow is (or has to be) loaded,
 * methods for fetching the entitlements, and so on.
 */
public abstract class InboundsSource implements DebugDumpable {

    private static final Trace LOGGER = TraceManager.getTrace(InboundsSource.class);

    /**
     * Current input data, mostly shadow object (in case of clockwork processing it may be full or repo-only, or maybe even null
     * - e.g. when currentObject is null in projection context). May contain a delta (sync or computed).
     *
     * It can be the association value as well.
     */
    @NotNull InboundSourceData sourceData;

    /** Describes how to execute the inbound processing. */
    @NotNull final ResourceObjectInboundProcessingDefinition inboundProcessingDefinition;

    @NotNull private final ResourceType resource;

    /** Background information for value provenance metadata for related inbound mappings. */
    @NotNull private final InboundMappingContextSpecification mappingContextSpecification;

    @NotNull final String humanReadableName;

    InboundsSource(
            @NotNull InboundSourceData sourceData,
            @NotNull ResourceObjectInboundProcessingDefinition inboundProcessingDefinition,
            @NotNull ResourceType resource,
            @NotNull InboundMappingContextSpecification mappingContextSpecification,
            @NotNull String humanReadableName) {
        this.sourceData = sourceData;
        this.inboundProcessingDefinition = inboundProcessingDefinition;
        this.resource = resource;
        this.mappingContextSpecification = mappingContextSpecification;
        this.humanReadableName = humanReadableName;
    }

    /**
     * Checks if we should process mappings from this source. This is mainly to avoid the cost of loading
     * from resource if there's no explicit reason to do so. See the implementation for details.
     */
    abstract boolean isEligibleForInboundProcessing(OperationResult result) throws SchemaException, ConfigurationException;

    /**
     * Returns the resource object.
     */
    @NotNull ResourceType getResource() {
        return resource;
    }

    /** Returns human-readable name of the context, for logging/reporting purposes. */
    @NotNull String getProjectionHumanReadableName() {
        return humanReadableName;
    }

    @Override
    public String debugDump(int indent) {
        StringBuilder sb = DebugUtil.createTitleStringBuilderLn(getClass(), indent);
        DebugUtil.debugDumpWithLabelLn(sb, "projection on", humanReadableName, indent + 1);
        DebugUtil.debugDumpWithLabel(sb, "source data", sourceData, indent + 1);
        return sb.toString();
    }

    /**
     * True if we are running under clockwork.
     */
    abstract boolean isClockwork();

    /**
     * Is the current projection being deleted? I.e. it (may or may not) exist now, but is not supposed to exist
     * after the clockwork is finished.
     *
     * Currently relevant only for clockwork-based execution. But (maybe soon) we'll implement it also for
     * pre-mappings.
     *
     * For some reason, we are interested only in the sync and primary delta here.
     * We ignore the synchronization decision, like in {@link LensProjectionContext#isDelete()}. (But why?)
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    abstract boolean isProjectionBeingDeleted();

    public abstract boolean isItemLoaded(@NotNull ItemPath path) throws SchemaException, ConfigurationException;
    public abstract boolean isFullShadowLoaded();
    public abstract boolean isShadowGone();

    /**
     * Adds value metadata to values in the current item and in a-priori delta. This is used for advanced metadata-aware
     * scenarios.
     */
    <V extends PrismValue, D extends ItemDefinition<?>> void setValueMetadata(
            Item<V, D> currentProjectionItem, ItemDelta<V, D> itemAPrioriDelta, OperationResult result)
            throws CommunicationException, ObjectNotFoundException, SchemaException, SecurityViolationException,
            ConfigurationException, ExpressionEvaluationException {
        // Currently relevant only for clockwork processing. Otherwise, it's a no-op.
    }

    // TODO move to context
    abstract String getChannel();

    /**
     * Loads the full shadow, if appropriate conditions are fulfilled. See the implementation for details.
     *
     * Currently relevant only for clockwork-based execution.
     */
    abstract void loadFullShadow(@NotNull InboundsContext context, OperationResult result)
            throws SchemaException, StopProcessingProjectionException;

    /**
     * Resolves the entitlements in the input data (a priori delta, current object).
     * Used in request creator, called from mappings creator.
     */
    abstract void resolveInputEntitlements(
            ContainerDelta<ShadowAssociationValueType> associationAPrioriDelta,
            ShadowAssociation currentAssociation,
            OperationResult result);

    /**
     * Provides the `entitlement` variable for mapping evaluation.
     * Used in request creator, called from mapping evaluator (!).
     */
    abstract void getEntitlementVariableProducer(
            @NotNull Source<?, ?> source, @Nullable PrismValue value, @NotNull VariablesMap variables);

    /**
     * Creates {@link MappingEvaluationRequest} object by providing the appropriate context to the mapping.
     */
    abstract <V extends PrismValue, D extends ItemDefinition<?>> MappingEvaluationRequest<V, D>
    createMappingRequest(MappingImpl<V, D> mapping);

    /**
     * Selects mappings appropriate for the current evaluation phase.
     * (The method is in this class, because here we have the object definition where defaults are defined.)
     *
     * @param resourceItemLocalCorrelatorDefined Is the correlator defined "locally" for particular resource object item
     * (currently attribute)?
     * @param correlationItemPaths What (focus) items are referenced by `items` correlators?
     */
    @NotNull List<InboundMappingConfigItem> selectMappingBeansForEvaluationPhase(
            @NotNull ItemPath itemPath,
            @NotNull List<InboundMappingConfigItem> mappings,
            boolean resourceItemLocalCorrelatorDefined,
            @NotNull Collection<ItemPath> correlationItemPaths) throws ConfigurationException {
        var currentPhase = getCurrentEvaluationPhase();
        var applicabilityEvaluator = new ApplicabilityEvaluator(
                getDefaultEvaluationPhases(), resourceItemLocalCorrelatorDefined, correlationItemPaths, currentPhase);
        var filteredMappings = applicabilityEvaluator.filterApplicableMappings(mappings);
        if (filteredMappings.size() < mappings.size()) {
            LOGGER.trace("{}: {} out of {} mapping(s) were filtered out because of evaluation phase '{}'",
                    itemPath, mappings.size() - filteredMappings.size(), mappings.size(), currentPhase);
        }
        return filteredMappings;
    }

    private @Nullable DefaultInboundMappingEvaluationPhasesType getDefaultEvaluationPhases() {
        return inboundProcessingDefinition.getDefaultInboundMappingEvaluationPhases();
    }

    abstract @NotNull InboundMappingEvaluationPhaseType getCurrentEvaluationPhase();

    /** Computes focus identity source information for given projection. Not applicable to pre-mappings. */
    abstract @Nullable FocusIdentitySourceType getFocusIdentitySource();

    abstract @Nullable IdentityItemConfiguration getIdentityItemConfiguration(@NotNull ItemPath itemPath) throws ConfigurationException;

    abstract ItemPath determineTargetPathExecutionOverride(ItemPath targetItemPath) throws ConfigurationException, SchemaException;

    public @NotNull ResourceObjectInboundProcessingDefinition getInboundProcessingDefinition() {
        return inboundProcessingDefinition;
    }

    public @NotNull InboundSourceData getSourceData() {
        return sourceData;
    }

    /** FIXME ugly hack */
    boolean hasDependentContext() throws SchemaException, ConfigurationException {
        return false;
    }

    /** Only for full processing. */
    @NotNull CachedShadowsUseType getCachedShadowsUse() throws SchemaException, ConfigurationException {
        throw new UnsupportedOperationException("Not implemented for " + this);
    }

    public MappingSpecificationType createMappingSpec(@Nullable String mappingName, @NotNull ItemDefinition<?> sourceDefinition) {
        return new MappingSpecificationType()
                .mappingName(mappingName)
                .definitionObjectRef(ObjectTypeUtil.createObjectRef(resource))
                .objectType(mappingContextSpecification.typeIdentificationBean())
                .associationType(
                        sourceDefinition instanceof ShadowAssociationDefinition assocDef ?
                                assocDef.getAssociationTypeName() :
                                mappingContextSpecification.associationTypeName())
                .tag(mappingContextSpecification.shadowTag());
    }
}
