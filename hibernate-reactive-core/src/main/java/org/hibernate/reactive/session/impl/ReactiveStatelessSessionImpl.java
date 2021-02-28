/* Hibernate, Relational Persistence for Idiomatic Java
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Copyright: Red Hat Inc. and Hibernate Authors
 */
package org.hibernate.reactive.session.impl;

import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.UnresolvableObjectException;
import org.hibernate.action.internal.BulkOperationCleanupAction;
import org.hibernate.bytecode.spi.BytecodeEnhancementMetadata;
import org.hibernate.cache.spi.access.EntityDataAccess;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.internal.Versioning;
import org.hibernate.engine.query.spi.HQLQueryPlan;
import org.hibernate.engine.query.spi.sql.NativeSQLQuerySpecification;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.SessionCreationOptions;
import org.hibernate.internal.SessionFactoryImpl;
import org.hibernate.internal.StatelessSessionImpl;
import org.hibernate.jpa.spi.NativeQueryTupleTransformer;
import org.hibernate.loader.custom.CustomQuery;
import org.hibernate.loader.custom.sql.SQLCustomQuery;
import org.hibernate.persister.entity.EntityPersister;
import org.hibernate.query.ParameterMetadata;
import org.hibernate.query.Query;
import org.hibernate.query.internal.ParameterMetadataImpl;
import org.hibernate.reactive.common.ResultSetMapping;
import org.hibernate.reactive.engine.impl.ReactivePersistenceContextAdapter;
import org.hibernate.reactive.id.impl.IdentifierGeneration;
import org.hibernate.reactive.loader.custom.impl.ReactiveCustomLoader;
import org.hibernate.reactive.persister.entity.impl.ReactiveEntityPersister;
import org.hibernate.reactive.pool.ReactiveConnection;
import org.hibernate.reactive.session.ReactiveNativeQuery;
import org.hibernate.reactive.session.ReactiveQuery;
import org.hibernate.reactive.session.ReactiveStatelessSession;
import org.hibernate.tuple.entity.EntityMetamodel;

import javax.persistence.Tuple;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.hibernate.reactive.id.impl.IdentifierGeneration.assignIdIfNecessary;
import static org.hibernate.reactive.util.impl.CompletionStages.completedFuture;

/**
 * An {@link ReactiveStatelessSession} implemented by extension of
 * the {@link StatelessSessionImpl} in Hibernate core. Extension was
 * preferred to delegation because there are places where
 * Hibernate core compares the identity of session instances.
 */
public class ReactiveStatelessSessionImpl extends StatelessSessionImpl
        implements ReactiveStatelessSession {

    private final ReactiveConnection proxyConnection;
    private final boolean allowBytecodeProxy;

    private final PersistenceContext persistenceContext = new ReactivePersistenceContextAdapter(this);

    public ReactiveStatelessSessionImpl(SessionFactoryImpl factory,
                                        SessionCreationOptions options,
                                        ReactiveConnection proxyConnection) {
        super(factory, options);
        this.proxyConnection = proxyConnection;
        allowBytecodeProxy = getFactory().getSessionFactoryOptions().isEnhancementAsProxyEnabled();
    }

    private LockOptions getNullSafeLockOptions(LockMode lockMode) {
        return new LockOptions( lockMode == null ? LockMode.NONE : lockMode );
    }

    @Override
    public PersistenceContext getPersistenceContext() {
        return persistenceContext;
    }

    @Override
    public Dialect getDialect() {
        return getJdbcServices().getDialect();
    }

    @Override
    public SharedSessionContractImplementor getSharedContract() {
        return this;
    }

    @Override
    public PersistenceContext getPersistenceContextInternal() {
        return persistenceContext;
    }

    @Override
    public ReactiveConnection getReactiveConnection() {
        return proxyConnection;
    }

    @Override
    public void checkTransactionNeededForUpdateOperation(String exceptionMessage) {
        //no-op because we don't support transactions
    }

    @Override
    public <T> CompletionStage<T> reactiveGet(Class<? extends T> entityClass, Object id) {
        return reactiveGet( entityClass, id, LockMode.NONE );
    }

    @Override
    public <T> CompletionStage<T> reactiveGet(Class<? extends T> entityClass, Object id, LockMode lockMode) {
        checkOpen();

        ReactiveEntityPersister persister = (ReactiveEntityPersister)
                getFactory().getMetamodel().entityPersister(entityClass);
        LockOptions lockOptions = getNullSafeLockOptions(lockMode);
        return persister.reactiveLoad( (Serializable) id, null, lockOptions, this )
                .thenApply( entity -> {
                    if ( getPersistenceContext().isLoadFinished() ) {
                        getPersistenceContext().clear();
                    }
                    return (T) entity;
                } );
    }

    @Override
    public ReactiveEntityPersister getEntityPersister(String entityName, Object object) throws HibernateException {
        return (ReactiveEntityPersister) super.getEntityPersister(entityName, object);
    }

    @Override
    public CompletionStage<Void> reactiveInsert(Object entity) {
        checkOpen();
        ReactiveEntityPersister persister = getEntityPersister( null, entity );
        return IdentifierGeneration.generateId( entity, persister, this, this )
                .thenCompose( id -> {
                    Object[] state = persister.getPropertyValues(entity);
                    if ( persister.isVersioned() ) {
                        boolean substitute = Versioning.seedVersion(
                                state,
                                persister.getVersionProperty(),
                                persister.getVersionType(),
                                this
                        );
                        if (substitute) {
                            persister.setPropertyValues( entity, state );
                        }
                    }

                    if ( persister.isIdentifierAssignedByInsert() ) {
                        return persister.insertReactive( state, entity, this )
                                .thenAccept( generatedId -> assignIdIfNecessary( entity, generatedId, persister,this ) );
                    }
                    else {
                        id = assignIdIfNecessary( id, entity, persister,this );
                        persister.setIdentifier( entity, id, this );
                        return persister.insertReactive( id, state, entity, this )
                                .thenApply( v-> null );
                    }
                } );
    }

    @Override
    public CompletionStage<Void> reactiveDelete(Object entity) {
        checkOpen();
        ReactiveEntityPersister persister = getEntityPersister( null, entity );
        Serializable id = persister.getIdentifier( entity, this );
        Object version = persister.getVersion( entity );
        return persister.deleteReactive( id, version, entity, this )
                .thenApply( v-> null );
    }

    @Override
    public CompletionStage<Void> reactiveUpdate(Object entity) {
        checkOpen();
        ReactiveEntityPersister persister = getEntityPersister( null, entity );
        Serializable id = persister.getIdentifier( entity, this );
        Object[] state = persister.getPropertyValues( entity );
        Object oldVersion;
        if ( persister.isVersioned() ) {
            oldVersion = persister.getVersion( entity );
            Object newVersion = Versioning.increment( oldVersion, persister.getVersionType(), this );
            Versioning.setVersion( state, newVersion, persister );
            persister.setPropertyValues( entity, state );
        }
        else {
            oldVersion = null;
        }
        return persister.updateReactive( id, state, null, false, null, oldVersion, entity, null, this )
                .thenApply( v-> null );
    }

    @Override
    public CompletionStage<Void> reactiveRefresh(Object entity) {
        return reactiveRefresh( entity, LockMode.NONE );
    }

    @Override
    public CompletionStage<Void> reactiveRefresh(Object entity, LockMode lockMode) {
        final ReactiveEntityPersister persister = getEntityPersister( null, entity );
        final Serializable id = persister.getIdentifier( entity, this );

        if ( persister.canWriteToCache() ) {
            final EntityDataAccess cacheAccess = persister.getCacheAccessStrategy();
            if ( cacheAccess != null ) {
                final Object ck = cacheAccess.generateCacheKey(
                        id,
                        persister,
                        getFactory(),
                        getTenantIdentifier()
                );
                cacheAccess.evict( ck );
            }
        }

        String previousFetchProfile = getLoadQueryInfluencers().getInternalFetchProfile();
        getLoadQueryInfluencers().setInternalFetchProfile( "refresh" );
        return persister.reactiveLoad( id, entity, getNullSafeLockOptions( lockMode ), this )
                .thenAccept( result -> {
                    if ( getPersistenceContext().isLoadFinished() ) {
                        getPersistenceContext().clear();
                    }
                    UnresolvableObjectException.throwIfNull( result, id, persister.getEntityName() );
                } )
                .whenComplete( (v,e) -> getLoadQueryInfluencers().setInternalFetchProfile( previousFetchProfile ) );
    }

    @Override
    public <R> ReactiveQueryImpl<R> createReactiveQuery(String queryString) {
        checkOpen();

        try {
            ParameterMetadataImpl paramMetadata =
                    getQueryPlan( queryString, false ).getParameterMetadata();
            ReactiveQueryImpl<R> query =
                    new ReactiveQueryImpl<>( this, paramMetadata, queryString );
            applyQuerySettingsAndHints( query );
            query.setComment( queryString );
            return query;
        }
        catch (RuntimeException e) {
            markForRollbackOnly();
            throw getExceptionConverter().convert( e );
        }
    }

    @Override
    public <R> ReactiveQuery<R> createReactiveQuery(String queryString, Class<R> resultType) {
        try {
            // do the translation
            final ReactiveQueryImpl<R> query = createReactiveQuery( queryString );
            resultClassChecking( resultType, query.unwrap( Query.class ) );
            return query;
        }
        catch (RuntimeException e) {
            throw getExceptionConverter().convert( e );
        }
    }

    private <T> void handleNativeQueryResult(ReactiveNativeQuery<T> query, Class<T> resultClass) {
        if ( Tuple.class.equals( resultClass ) ) {
            query.setResultTransformer( new NativeQueryTupleTransformer() );
        }
        else {
            query.addEntity( "alias1", resultClass.getName(), LockMode.READ );
        }
    }

    @Override
    public <T> ReactiveNativeQuery<T> createReactiveNativeQuery(String sqlString) {
        checkOpen();

        try {
            ParameterMetadata params = getFactory().getQueryPlanCache()
                    .getSQLParameterMetadata(sqlString, false);
            ReactiveNativeQueryImpl<T> query =
                    new ReactiveNativeQueryImpl<>(sqlString, false, this, params );
            query.setComment( "dynamic native SQL query" );
            applyQuerySettingsAndHints( query );
            return query;
        }
        catch ( RuntimeException he ) {
            throw getExceptionConverter().convert( he );
        }
    }

    @Override
    public <T> ReactiveNativeQuery<T> createReactiveNativeQuery(String sqlString, Class<T> resultClass) {
        try {
            ReactiveNativeQuery<T> query = createReactiveNativeQuery( sqlString );
            handleNativeQueryResult( query, resultClass );
            return query;
        }
        catch ( RuntimeException he ) {
            throw getExceptionConverter().convert( he );
        }
    }

    @Override
    public <T> ReactiveNativeQuery<T> createReactiveNativeQuery(String sqlString, String resultSetMapping) {
        try {
            ReactiveNativeQuery<T> query = createReactiveNativeQuery( sqlString );
            query.setResultSetMapping( resultSetMapping );
            return query;
        }
        catch ( RuntimeException he ) {
            throw getExceptionConverter().convert( he );
        }
    }

    @Override
    public <T> CompletionStage<List<T>> reactiveList(String query, QueryParameters parameters) {
        checkOpen();
        parameters.validateParameters();

        HQLQueryPlan plan = parameters.getQueryPlan();
        ReactiveHQLQueryPlan reactivePlan = plan == null
                ? getQueryPlan( query, false )
                : (ReactiveHQLQueryPlan) plan;

        return reactivePlan.performReactiveList( parameters, this )
                .whenComplete( (list, x) -> {
                    getPersistenceContext().clear();
                    afterOperation( x == null );
                } )
                //TODO: this typecast is rubbish
                .thenApply( list -> (List<T>) list );
    }

    @Override
    public <T> CompletionStage<List<T>> reactiveList(NativeSQLQuerySpecification spec, QueryParameters parameters) {
        checkOpen();

        ReactiveCustomLoader loader = new ReactiveCustomLoader(
                getNativeQueryPlan( spec ).getCustomQuery(),
                getFactory()
        );

        return loader.reactiveList(this, parameters)
                .whenComplete((r, x) -> {
                    getPersistenceContext().clear();
                    afterOperation(x == null);
                })
                //TODO: this typecast is rubbish
                .thenApply( list -> (List<T>) list );
    }

    @Override
    protected ReactiveHQLQueryPlan getQueryPlan(String query, boolean shallow) throws HibernateException {
        return (ReactiveHQLQueryPlan) super.getQueryPlan( query, shallow );
    }

    @Override
    public CompletionStage<Integer> executeReactiveUpdate(String query, QueryParameters parameters) {
        checkOpen();
        parameters.validateParameters();
        ReactiveHQLQueryPlan reactivePlan = getQueryPlan( query, false );
        return reactivePlan.performExecuteReactiveUpdate( parameters, this )
                .whenComplete( (count, x) -> {
                    getPersistenceContext().clear();
                    afterOperation( x == null );
                } );
    }

    @Override
    public CompletionStage<Integer> executeReactiveUpdate(NativeSQLQuerySpecification specification,
                                                          QueryParameters parameters) {
        checkOpen();
        parameters.validateParameters();

        ReactiveNativeSQLQueryPlan reactivePlan = //getNativeQueryPlan( specification );
                new ReactiveNativeSQLQueryPlan(
                        specification.getQueryString(),
                        new SQLCustomQuery(
                                specification.getQueryString(),
                                specification.getQueryReturns(),
                                specification.getQuerySpaces(),
                                getFactory()
                        ) );
        return  reactivePlan.performExecuteReactiveUpdate( parameters, this )
                .whenComplete( (count, x) -> {
                    getPersistenceContext().clear();
                    afterOperation( x == null );
                } );
    }

    @Override
    public void addBulkCleanupAction(BulkOperationCleanupAction action) {
        action.getAfterTransactionCompletionProcess()
                .doAfterTransactionCompletion( true, this );
    }

    @Override
    public List<?> list(String query, QueryParameters queryParameters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<?> listCustomQuery(CustomQuery customQuery, QueryParameters queryParameters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> ResultSetMapping<T> getResultSetMapping(Class<T> resultType, String mappingName) {
        return ResultSetMappings.resultSetMapping( resultType, mappingName, getFactory() );
    }

    private Object createProxy(EntityKey entityKey) {
        final Object proxy = entityKey.getPersister().createProxy( entityKey.getIdentifier(), this );
        getPersistenceContext().addProxy( entityKey, proxy );
        return proxy;
    }

    @Override
    public CompletionStage<Object> reactiveInternalLoad(String entityName, Serializable id, boolean eager, boolean nullable) {
        checkOpen();

        EntityPersister persister = getFactory().getMetamodel().entityPersister( entityName );
        EntityKey entityKey = generateEntityKey( id, persister );

        // first, try to load it from the temp PC associated to this SS
        PersistenceContext persistenceContext = getPersistenceContext();
        Object loaded = persistenceContext.getEntity( entityKey );
        if ( loaded != null ) {
            // we found it in the temp PC.  Should indicate we are in the midst of processing a result set
            // containing eager fetches via join fetch
            return completedFuture(loaded);
        }

        if ( !eager ) {
            // caller did not request forceful eager loading, see if we can create
            // some form of proxy

            // first, check to see if we can use "bytecode proxies"

            EntityMetamodel entityMetamodel = persister.getEntityMetamodel();
            BytecodeEnhancementMetadata bytecodeEnhancementMetadata = entityMetamodel.getBytecodeEnhancementMetadata();
            if ( allowBytecodeProxy && bytecodeEnhancementMetadata.isEnhancedForLazyLoading() ) {

                // if the entity defines a HibernateProxy factory, see if there is an
                // existing proxy associated with the PC - and if so, use it
                if ( entityMetamodel.getTuplizer().getProxyFactory() != null ) {
                    final Object proxy = persistenceContext.getProxy( entityKey );

                    if ( proxy != null ) {
//                        if ( LOG.isTraceEnabled() ) {
//                            LOG.trace( "Entity proxy found in session cache" );
//                        }
//                        if ( LOG.isDebugEnabled() && ( (HibernateProxy) proxy ).getHibernateLazyInitializer().isUnwrap() ) {
//                            LOG.debug( "Ignoring NO_PROXY to honor laziness" );
//                        }

                        return completedFuture( persistenceContext.narrowProxy( proxy, persister, entityKey, null ) );
                    }

                    // specialized handling for entities with subclasses with a HibernateProxy factory
                    if ( entityMetamodel.hasSubclasses() ) {
                        // entities with subclasses that define a ProxyFactory can create
                        // a HibernateProxy.
//                        LOG.debugf( "Creating a HibernateProxy for to-one association with subclasses to honor laziness" );
                        return completedFuture( createProxy( entityKey ) );
                    }
                    return completedFuture( bytecodeEnhancementMetadata.createEnhancedProxy( entityKey, false, this ) );
                }
                else if ( !entityMetamodel.hasSubclasses() ) {
                    return completedFuture( bytecodeEnhancementMetadata.createEnhancedProxy( entityKey, false, this ) );
                }
                // If we get here, then the entity class has subclasses and there is no HibernateProxy factory.
                // The entity will get loaded below.
            }
            else {
                if ( persister.hasProxy() ) {
                    final Object existingProxy = persistenceContext.getProxy( entityKey );
                    if ( existingProxy != null ) {
                        return completedFuture( persistenceContext.narrowProxy( existingProxy, persister, entityKey, null ) );
                    }
                    else {
                        return completedFuture( createProxy( entityKey ) );
                    }
                }
            }
        }

        // otherwise immediately materialize it

        // IMPLEMENTATION NOTE: increment/decrement the load count before/after getting the value
        //                      to ensure that #get does not clear the PersistenceContext.
        persistenceContext.beforeLoad();
        return this.<Object>reactiveGet( persister.getMappedClass(), id )
                .whenComplete( (r, e) -> persistenceContext.afterLoad()  );
    }

    @Override
    public void close() {
        proxyConnection.close();
        super.close();
    }
}
