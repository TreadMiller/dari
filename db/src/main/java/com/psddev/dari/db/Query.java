package com.psddev.dari.db;

import com.psddev.dari.util.HtmlObject;
import com.psddev.dari.util.HtmlWriter;
import com.psddev.dari.util.ObjectUtils;
import com.psddev.dari.util.PaginatedResult;
import com.psddev.dari.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Query over objects in a {@linkplain Database database}.
 *
 * <p>Typical use looks like:
 *
 * <p><blockquote><pre>
 * Query&lt;Article&gt; query = Query.from(Article.class);
 * query.where("author = ?", author);
 * query.sortAscending("headline");
 * List&lt;Article&gt; articles = query.select();
 * </pre></blockquote>
 *
 * <p>Which is roughly equivalent to the following SQL:
 *
 * <p><blockquote><pre>
 * SELECT *
 * FROM Article
 * WHERE author = ?
 * ORDER BY headline ASC
 * </pre></blockquote>
 *
 * <p>Most methods can be chained so the above query can be rewritten as:
 *
 * <p><blockquote><pre>
 * List&lt;Article&gt; articles = Query.
 * &nbsp;   from(Article.class).
 * &nbsp;   where("author = ?", author).
 * &nbsp;   sortAscending("headline").
 * &nbsp;   select();
 * </pre></blockquote>
 *
 * <p>The {@linkplain #and compound methods} provide a convenient way to
 * split the {@linkplain PredicateParser.Static#parse predicate string} around the
 * logical flow of the program:
 *
 * <p><blockquote><pre>
 * Query&lt;Article&gt; query = Query.from(Article.class);
 * query.where("author = ?", author);
 * query.sortAscending("headline");
 * if (...) {
 * &nbsp;   query.and("topic = ?", topic1);
 * } else {
 * &nbsp;   query.and("topic = ?", topic2);
 * }
 * </pre></blockquote>
 *
 * <p>Or you can use the predicate classes directly for more control over
 * the whole process:
 *
 * <p><blockquote><pre>
 * Query&lt;Article&gt; query = Query.from(Article.class);
 * String comparison = ...
 * String compound = ...
 * Predicate predicate = new ComparisonPredicate(comparison, null, "author", author);
 * if (...) {
 * &nbsp;   predicate = new CompoundPredicate(compound, Arrays.asList(
 * &nbsp;           predicate,
 * &nbsp;           new ComparisonPredicate(comparison, null, "topic", topic1)));
 * } else {
 * &nbsp;   predicate = new CompoundPredicate(compound, Arrays.asList(
 * &nbsp;           predicate,
 * &nbsp;           new ComparisonPredicate(comparison, null, "topic", topic2)));
 * }
 * List&lt;Article&gt; articles = query.where(predicate).select();
 * </pre></blockquote>
 *
 * <p>Finally, joins are not supported, but subqueries are:
 *
 * <p><blockquote><pre>
 * Query&lt;Author&gt; authorQuery = Query.
 * &nbsp;   from(Author.class).
 * &nbsp;   where("name = ?", name);
 * Query&lt;Article&gt; articleQuery = Query.
 * &nbsp;   from(Article.class).
 * &nbsp;   where("author = ?", authorQuery).
 * &nbsp;   sortAscending("headline");
 * </pre></blockquote>
 *
 * @see <a href="http://developer.apple.com/mac/library/documentation/Cocoa/Conceptual/Predicates/predicates.html">Cocoa Predicates</a>
 * @see <a href="http://msdn.microsoft.com/en-us/netframework/aa904594.aspx">LINQ</a>
 */
public class Query<E> extends Record implements Cloneable, HtmlObject {

    public static final Object MISSING_VALUE = new Object() {
        @Override
        public String toString() {
            return "missing";
        }
    };

    public static final String ID_KEY = "_id";
    public static final String TYPE_KEY = "_type";
    public static final String ANY_KEY = "_any";

    public static final String CREATOR_EXTRA = "dari.creatorQuery";

    private final String group;
    private final transient Class<?> objectClass;

    private Predicate predicate;
    private List<Sorter> sorters;
    private List<String> fields;
    private transient Database database;
    private boolean isResolveToReferenceOnly;
    private Double timeout;
    private transient Map<String, Object> options;
    private transient HashSet<String> extraSourceColumns = new HashSet<String>();

    private final transient Map<String, Object> facetedFields = new HashMap<String, Object>();
    private transient Query<?> facetedQuery;

    /**
     * Queries over objects of types that are compatible with the given
     * {@code objectClass}.
     */
    public static <T> Query<T> from(Class<T> objectClass) {
        return new Query<T>(objectClass != null ? objectClass.getName() : null, objectClass);
    }

    /** Queries over all objects. */
    public static Query<Object> fromAll() {
        return new Query<Object>(null, null);
    }

    /**
     * Queries over objects of types that are compatible with the given
     * {@code type}.
     */
    public static Query<Object> fromType(ObjectType type) {
        if (type == null) {
            return new Query<Object>(null, null);
        } else {
            Query<Object> query = new Query<Object>(type.getInternalName(), type.getObjectClass());
            query.setDatabase(type.getState().getDatabase());
            return query;
        }
    }

    /**
     * Queries over objects of types that belong to the given
     * {@link ObjectType#getGroups group}.
     */
    public static Query<Object> fromGroup(String group) {
        return new Query<Object>(group, null);
    }

    /** Queries over objects that match the given {@code query}. */
    public static <T> Query<T> fromQuery(Query<T> query) {
        return query.clone();
    }

    /**
     * Creates an instance that will query over objects of types that
     * are compatible with the given {@code group}.
     */
    protected Query(String group, Class<?> objectClass) {
        this.group = Object.class.getName().equals(group) ? null : group;
        this.objectClass = Object.class.equals(objectClass) ? null : objectClass;
    }

    @SuppressWarnings("all")
    protected Query() {
        this.group = null;
        this.objectClass = null;
    }

    /**
     * Returns the {@linkplain ObjectType#getGroups group} that identifies
     * the types of objects to query.
     */
    public String getGroup() {
        return group;
    }

    /** Returns {@code true} if this queries over all objects. */
    public boolean isFromAll() {
        return getGroup() == null;
    }

    public Class<?> getObjectClass() {
        return objectClass;
    }

    /** Returns the predicate for filtering the result. */
    public Predicate getPredicate() {
        return predicate;
    }

    /** Sets the predicate for filtering the result. */
    public void setPredicate(Predicate predicate) {
        this.predicate = predicate;
    }

    /** Returns the list of sorters applied to the result. */
    public List<Sorter> getSorters() {
        if (sorters == null) {
            sorters = new ArrayList<Sorter>();
        }
        return sorters;
    }

    /** Sets the list of sorters applied to the result. */
    public void setSorters(List<Sorter> sorters) {
        this.sorters = sorters;
    }

    public List<String> getFields() {
        return this.fields;
    }

    public boolean isReferenceOnly() {
        return this.fields != null && this.fields.isEmpty();
    }

    public void setFields(List<String> fields) {
        this.fields = fields;
    }

    /**
     * Returns the database to be used with the read methods.
     *
     * @return The {@linkplain Database.Static#getDefault default database}
     * if not previously set.
     */
    public Database getDatabase() {
        if (database == null) {
            Database defaultDatabase = Database.Static.getDefault();
            Database source = null;

            // Change the query database if it's only querying over a single
            // type that has a source database.
            for (ObjectType groupType : defaultDatabase.getEnvironment().getTypesByGroup(getGroup())) {
                for (ObjectType type : groupType.findConcreteTypes()) {
                    Database typeSource = type.getSourceDatabase();

                    if (typeSource == null) {
                        source = null;
                        break;

                    } else if (source == null) {
                        source = typeSource;

                    } else if (!source.equals(typeSource)) {
                        source = null;
                        break;
                    }
                }
            }

            database = source != null ? source : defaultDatabase;
        }

        return database;
    }

    /** Sets the database to be used with the read methods. */
    public void setDatabase(Database database) {
        this.database = database;
    }

    public boolean isResolveToReferenceOnly() {
        return isResolveToReferenceOnly;
    }

    public void setResolveToReferenceOnly(boolean isResolveToReferenceOnly) {
        this.isResolveToReferenceOnly = isResolveToReferenceOnly;
    }

    public Double getTimeout() {
        return timeout;
    }

    public void setTimeout(Double timeout) {
        this.timeout = timeout;
    }

    /**
     * Returns the map of custom options that are passed to the
     * underlying database.
     */
    public Map<String, Object> getOptions() {
        if (options == null) {
            options = new HashMap<String, Object>();
        }
        return options;
    }

    /**
     * Sets the map of custom options that are passed to the
     * underlying database.
     */
    public void setOptions(Map<String, Object> options) {
        this.options = options;
    }

    /**
     * Creates an absolute version of the predicate that can be embedded
     * in other queries.
     */
    public Predicate createAbsolutePredicate() {
        Predicate predicate = getPredicate();
        return predicate != null ? addPrefix(getGroup() + "/", predicate) : null;
    }

    private Predicate addPrefix(String prefix, Predicate predicate) {
        if (predicate instanceof CompoundPredicate) {
            CompoundPredicate compound = (CompoundPredicate) predicate;
            List<Predicate> children = new ArrayList<Predicate>();
            for (Predicate child : compound.getChildren()) {
                children.add(addPrefix(prefix, child));
            }
            return new CompoundPredicate(compound.getOperator(), children);

        } else if (predicate instanceof ComparisonPredicate) {
            ComparisonPredicate comparison = (ComparisonPredicate) predicate;
            String key = comparison.getKey();
            if (!key.startsWith("_")) {
                key = prefix + key;
            }
            return new ComparisonPredicate(
                    comparison.getOperator(),
                    comparison.isIgnoreCase(),
                    key,
                    comparison.getValues());

        } else {
            return predicate;
        }
    }

    // --- Fluent methods ---

    /**
     * Combines the given {@code predicate} with the current one using
     * {@code AND} logic. If the current predicate is {@code null},
     * the given {@code predicate} replaces it. For example:
     *
     * <p><blockquote><pre>
     * Query&lt;Article&gt; query = Query.from(Article.class);
     * for (...) {
     * &nbsp;   query.and("tags = ?", tag);
     * }
     * </pre></blockquote>
     *
     * <p>is equivalent to:
     *
     * <p><blockquote><pre>
     * Query&lt;Article&gt; query = Query.from(Article.class);
     * for (...) {
     * &nbsp;   if (isFirst) {
     * &nbsp;       query.where("tags = ?", tag);
     * &nbsp;   } else {
     * &nbsp;       query.and("tags = ?", tag);
     * &nbsp;   }
     * }
     * </pre></blockquote>
     */
    public Query<E> and(Predicate predicate) {
        if (predicate != null) {
            Predicate lastPredicate = getPredicate();
            setPredicate(lastPredicate != null ?
                    CompoundPredicate.combine(PredicateParser.AND_OPERATOR, lastPredicate, predicate) :
                    predicate);
        }
        return this;
    }

    /**
     * Parses the given {@linkplain PredicateParser.Static#parse predicateString}
     * with the given {@code parameters} and {@linkplain #and(Predicate)
     * adds it} to the current one.
     */
    public Query<E> and(String predicateString, Object... parameters) {
        return and(PredicateParser.Static.parse(predicateString, parameters));
    }

    /** @see #and(Predicate) */
    public Query<E> where(Predicate predicate) {
        return and(predicate);
    }

    /** @see #and(String, Object...) */
    public Query<E> where(String predicateString, Object... parameters) {
        return and(predicateString, parameters);
    }

    /**
     * Combines the given {@code predicate} with the current one using
     * {@code OR} logic. If the current predicate is {@code null},
     * the given {@code predicate} replaces it.
     */
    public Query<E> or(Predicate predicate) {
        if (predicate != null) {
            Predicate lastPredicate = getPredicate();
            setPredicate(lastPredicate != null
                    ? CompoundPredicate.combine(PredicateParser.OR_OPERATOR, lastPredicate, predicate)
                    : predicate);
        }
        return this;
    }

    /**
     * Parses the given {@linkplain PredicateParser.Static#parse predicateString}
     * with the given {@code parameters} and {@linkplain #or(Predicate)
     * adds it} to the current one.
     */
    public Query<E> or(String predicateString, Object... parameters) {
        return or(PredicateParser.Static.parse(predicateString, parameters));
    }

    /**
     * Combines the given {@code predicate} with the current one using
     * {@code NOT} logic. If the current predicate is {@code null},
     * the given {@code predicate} replaces it.
     */
    public Query<E> not(Predicate predicate) {
        if (predicate != null) {
            predicate = new CompoundPredicate(PredicateParser.NOT_OPERATOR, Arrays.asList(predicate));
            Predicate lastPredicate = getPredicate();
            setPredicate(lastPredicate != null
                    ? CompoundPredicate.combine(PredicateParser.AND_OPERATOR, lastPredicate, predicate)
                    : predicate);
        }
        return this;
    }

    /**
     * Parses the given {@linkplain PredicateParser.Static#parse predicateString}
     * with the given {@code parameters} and {@linkplain #not(Predicate)
     * adds it} to the current one.
     */
    public Query<E> not(String predicateString, Object... parameters) {
        return not(PredicateParser.Static.parse(predicateString, parameters));
    }

    /** Adds the given {@code sorter}. */
    public Query<E> sort(Sorter sorter) {
        getSorters().add(sorter);
        return this;
    }

    /** Adds a sorter with the given {@code operator} and {@code options}. */
    public Query<E> sort(String operator, Object... options) {
        return sort(new Sorter(operator, options != null ? Arrays.asList(options) : Collections.emptyList()));
    }

    /**
     * Adds a sorter that prioritizes the smaller values associated
     * with the given {@code key}.
     */
    public Query<E> sortAscending(String key) {
        key = Static.getCanonicalKey(key);
        sort(new Sorter(Sorter.ASCENDING_OPERATOR, Arrays.asList(key)));
        return this;
    }

    /**
     * Adds a sorter that prioritizes the larger values associated
     * with the given {@code key}.
     */
    public Query<E> sortDescending(String key) {
        key = Static.getCanonicalKey(key);
        sort(new Sorter(Sorter.DESCENDING_OPERATOR, Arrays.asList(key)));
        return this;
    }

    /**
     * Adds a sorter that prioritizes the values closest to the given
     * {@code location}.
     */
    public Query<E> sortClosest(String key, Location location) {
        key = Static.getCanonicalKey(key);
        sort(new Sorter(Sorter.CLOSEST_OPERATOR, Arrays.asList(key, location)));
        return this;
    }

    /**
     * Adds a sorter that prioritizes the values farthest from the given
     * {@code location}.
     */
    public Query<E> sortFarthest(String key, Location location) {
        key = Static.getCanonicalKey(key);
        sort(new Sorter(Sorter.FARTHEST_OPERATOR, Arrays.asList(key, location)));
        return this;
    }

    /**
     * Adds a sorter that prioritizes the items matching the given
     * {@code predicate}.
     */
    public Query<E> sortRelevant(double weight, Predicate predicate) {
        sort(new Sorter(Sorter.RELEVANT_OPERATOR, Arrays.<Object>asList(weight, predicate)));
        return this;
    }

    /**
     * Adds a sorter that prioritizes the items matching the given
     * {@link PredicateParser.Static#parse predicateString}.
     */
    public Query<E> sortRelevant(double weight, String predicateString, Object... parameters) {
        return sortRelevant(weight, PredicateParser.Static.parse(predicateString, parameters));
    }

    public Query<E> fields(String... fields) {
        if (this.fields == null) {
            this.fields = new ArrayList<String>();
        }
        Collections.addAll(this.fields, fields);
        return this;
    }

    public Query<E> allFields() {
        setFields(null);
        return this;
    }

    public Query<E> referenceOnly() {
        setFields(new ArrayList<String>());
        return this;
    }

    public Query<E> using(Database database) {
        setDatabase(database);
        return this;
    }

    public Query<E> usingFirst(Class<? extends Database> databaseClass) {
        setDatabase(Database.Static.getFirst(databaseClass));
        return this;
    }

    public Query<E> resolveToReferenceOnly() {
        setResolveToReferenceOnly(true);
        return this;
    }

    public Query<E> timeout(Double timeout) {
        setTimeout(timeout);
        return this;
    }

    /** Adds a custom option with the given {@code key} and {@code value}. */
    public Query<E> option(String key, Object value) {
        getOptions().put(key, value);
        return this;
    }

    /**
     * Returns all types that belong to the group in the given
     * {@code environment}.
     */
    public Set<ObjectType> getConcreteTypes(DatabaseEnvironment environment) {
        Set<ObjectType> types = environment.getTypesByGroup(getGroup());
        for (Iterator<ObjectType> i = types.iterator(); i.hasNext(); ) {
            ObjectType type = i.next();
            if (!type.isConcrete()) {
                i.remove();
            }
        }
        return types;
    }

    private MappedKey mapKey(DatabaseEnvironment environment, String key, boolean checkDenormalized) {
        MappedKey specialMappedKey = SPECIAL_MAPPED_KEYS.get(key);
        if (specialMappedKey != null) {
            return specialMappedKey;
        }

        List<ObjectField> fields = null;

        Set<ObjectType> fieldTypes;
        if (isFromAll()) {
            fieldTypes = Collections.emptySet();

        } else {
            ObjectType initialType = environment.getTypeByName(getGroup());
            fieldTypes = initialType != null ?
                    Collections.singleton(initialType) :
                    Collections.<ObjectType>emptySet();
        }

        boolean hasMore = true;
        String keyFirst;
        String keyRest = key;
        ObjectType type;
        Set<ObjectType> subQueryTypes = null;
        String subQueryKey = null;

        while (hasMore) {
            int slashAt = keyRest.indexOf('/');

            if (slashAt < 0) {
                keyFirst = keyRest;
                hasMore = false;

            } else {
                keyFirst = keyRest.substring(0, slashAt);
                keyRest = keyRest.substring(slashAt + 1);
            }

            type = environment.getTypeByName(keyFirst);
            if (type != null) {
                fieldTypes = Collections.singleton(type);

            } else {
                ObjectField field = environment.getField(keyFirst);

                if (field == null) {
                    for (ObjectType fieldType : fieldTypes) {
                        field = fieldType.getField(keyFirst);
                        if (field != null) {
                            fieldTypes = field.getTypes();
                            break;
                        }
                    }
                }

                if (field != null) {
                    if (hasMore && ObjectField.RECORD_TYPE.equals(field.getInternalItemType())) {
                        boolean isEmbedded = field.isEmbedded();

                        if (!isEmbedded) {
                            for (ObjectType fieldType : fieldTypes) {
                                if (fieldType.isEmbedded()) {
                                    isEmbedded = true;
                                    break;
                                }
                            }
                        }

                        if (checkDenormalized && !isEmbedded) {
                            isEmbedded = field.isDenormalized();

                            if (!isEmbedded) {
                                for (ObjectType fieldType : fieldTypes) {
                                    if (fieldType.isDenormalized()) {
                                        isEmbedded = true;
                                        break;
                                    }
                                }
                            }
                        }

                        if (!isEmbedded) {
                            hasMore = false;
                            subQueryTypes = fieldTypes;
                            subQueryKey = keyRest;
                        }
                    }

                    if (fields == null) {
                        fields = new ArrayList<ObjectField>();
                    }
                    fields.add(field);

                } else {
                    fields = null;
                    break;
                }
            }
        }

        if (fields == null) {
            throw new NoFieldException(getGroup(), key);
        }

        Set<ObjectIndex> indexes = new HashSet<ObjectIndex>();

        for (ObjectField field : fields) {
            String fieldName = field.getInternalName();
            indexes.clear();

            for (ObjectIndex index : field.getParent().getIndexes()) {
                if (index.getFields().contains(fieldName)) {
                    indexes.add(index);
                }
            }

            if (indexes.isEmpty()) {
                throw new NoIndexException(field);
            }
        }

        StandardMappedKey standardMappedKey = new StandardMappedKey();
        standardMappedKey.fields = fields;
        standardMappedKey.indexes = indexes;
        standardMappedKey.subQueryTypes = subQueryTypes;
        standardMappedKey.subQueryKey = subQueryKey;
        return standardMappedKey;
    }

    /**
     * Maps the given {@code key} to a field of a type in the given
     * {@code environment}. This is a helper method for database
     * implementations and isn't meant for general consumption.
     *
     * @throws NoFieldException If there isn't a field associated with
     *         the given {@code key}.
     * @throws NoIndexException If the field associated with the given
     *         {@code key} isn't indexed.
     */
    public MappedKey mapEmbeddedKey(DatabaseEnvironment environment, String key) {
        return mapKey(environment, key, false);
    }

    public MappedKey mapDenormalizedKey(DatabaseEnvironment environment, String key) {
        return mapKey(environment, key, true);
    }

    public interface MappedKey {

        public static final MappedKey ID = new SpecialMappedKey(ObjectField.UUID_TYPE);
        public static final MappedKey TYPE = new SpecialMappedKey(ObjectField.UUID_TYPE);
        public static final MappedKey ANY = new SpecialMappedKey(ObjectField.TEXT_TYPE);

        public String getIndexKey(ObjectIndex index);

        public String getInternalType();

        public boolean isInternalCollectionType();

        public ObjectField getField();

        public Set<ObjectIndex> getIndexes();

        public boolean hasSubQuery();

        public Query<?> getSubQueryWithComparison(ComparisonPredicate comparison);

        public Query<?> getSubQueryWithSorter(Sorter sorter, int index);
    }

    private static final Map<String, MappedKey> SPECIAL_MAPPED_KEYS; static {
        Map<String, MappedKey> m = new HashMap<String, MappedKey>();
        m.put(ID_KEY, MappedKey.ID);
        m.put(TYPE_KEY, MappedKey.TYPE);
        m.put(ANY_KEY, MappedKey.ANY);
        SPECIAL_MAPPED_KEYS = m;
    }

    private static class StandardMappedKey implements MappedKey {

        private List<ObjectField> fields;
        private Set<ObjectIndex> indexes;
        private Set<ObjectType> subQueryTypes;
        private String subQueryKey;

        public String getIndexKey(ObjectIndex index) {
            StringBuilder indexKeyBuilder = new StringBuilder();

            if (index == null) {
                indexKeyBuilder.append(fields.get(0).getUniqueName());
                for (int i = 1, size = fields.size(); i < size; ++ i) {
                    indexKeyBuilder.append('/');
                    indexKeyBuilder.append(fields.get(i).getInternalName());
                }

            } else {
                if (fields.size() == 1) {
                    if (index.getParent() instanceof ObjectType) {
                        indexKeyBuilder.append(index.getJavaDeclaringClassName());
                        indexKeyBuilder.append('/');
                    }

                } else {
                    indexKeyBuilder.append(fields.get(0).getUniqueName());
                    indexKeyBuilder.append('/');
                    for (int i = 1, size = fields.size() - 1; i < size; ++ i) {
                        indexKeyBuilder.append(fields.get(i).getInternalName());
                        indexKeyBuilder.append('/');
                    }
                }

                Iterator<String> indexFieldsIterator = index.getFields().iterator();
                indexKeyBuilder.append(indexFieldsIterator.next());
                while (indexFieldsIterator.hasNext()) {
                    indexKeyBuilder.append(',');
                    indexKeyBuilder.append(indexFieldsIterator.next());
                }
            }

            return indexKeyBuilder.toString();
        }

        @Override
        public String getInternalType() {
            return fields.get(fields.size() - 1).getInternalItemType();
        }

        @Override
        public boolean isInternalCollectionType() {
            for (ObjectField field : fields) {
                if (field.isInternalCollectionType()) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public ObjectField getField() {
            return fields.get(fields.size() - 1);
        }

        @Override
        public Set<ObjectIndex> getIndexes() {
            return indexes;
        }

        @Override
        public boolean hasSubQuery() {
            return subQueryTypes != null;
        }

        @Override
        public Query<?> getSubQueryWithComparison(ComparisonPredicate comparison) {
            if (subQueryTypes == null) {
                return comparison.findValueQuery();
            }

            Query<?> subQuery = Query.fromAll();
            String keySuffix = "/" + subQueryKey;

            for (ObjectType type : subQueryTypes) {
                subQuery.or(new ComparisonPredicate(
                        comparison.getOperator(),
                        comparison.isIgnoreCase(),
                        type.getInternalName() + keySuffix,
                        comparison.getValues()));
            }

            return subQuery;
        }

        @Override
        public Query<?> getSubQueryWithSorter(Sorter sorter, int index) {
            if (subQueryTypes == null) {
                return null;
            }

            Query<?> subQuery = Query.fromAll();
            String keySuffix = "/" + subQueryKey;

            for (ObjectType type : subQueryTypes) {
                List<Object> options = new ArrayList<Object>(sorter.getOptions());
                options.set(index, type.getInternalName() + keySuffix);
                subQuery.sort(new Sorter(sorter.getOperator(), options));
                break;
            }

            return subQuery;
        }
    }

    private static class SpecialMappedKey implements MappedKey {

        private final String internalType;

        public SpecialMappedKey(String internalType) {
            this.internalType = internalType;
        }

        @Override
        public String getIndexKey(ObjectIndex index) {
            return null;
        }

        @Override
        public String getInternalType() {
            return internalType;
        }

        @Override
        public boolean isInternalCollectionType() {
            return false;
        }

        @Override
        public ObjectField getField() {
            return null;
        }

        @Override
        public Set<ObjectIndex> getIndexes() {
            return Collections.emptySet();
        }

        @Override
        public boolean hasSubQuery() {
            return false;
        }

        @Override
        public Query<?> getSubQueryWithComparison(ComparisonPredicate comparison) {
            return null;
        }

        @Override
        public Query<?> getSubQueryWithSorter(Sorter sorter, int index) {
            return null;
        }
    }

    /**
     * Thrown when a key used within a query references a field that doesn't
     * exist.
     */
    @SuppressWarnings("serial")
    public static class NoFieldException extends RuntimeException {

        private final String group;
        private final String key;

        public NoFieldException(String group, String key) {
            super(String.format("Can't query [%s] using [%s]!", group, key));
            this.group = group;
            this.key = key;
        }

        public String getGroup() {
            return group;
        }

        public String getKey() {
            return key;
        }
    }

    /**
     * Thrown when a key used within a query references a field that's
     * not indexed.
     */
    @SuppressWarnings("serial")
    public static class NoIndexException extends RuntimeException {

        private final ObjectField field;

        public NoIndexException(ObjectField field) {
            super(String.format("Can't query [%s] because it's not indexed!", field.getUniqueName()));
            this.field = field;
        }

        /** Returns the field that's not indexed. */
        public ObjectField getField() {
            return field;
        }
    }

    /**
     * Maps all keys used in this query to the fields of the types in the
     * given {@code environment}. This is a helper method for database
     * implementations and isn't meant for general consumption.
     *
     * @see #mapEmbeddedKey
     */
    public Map<String, MappedKey> mapEmbeddedKeys(DatabaseEnvironment environment) {
        Map<String, MappedKey> mappedKeys = new HashMap<String, MappedKey>();

        addMappedPredicate(mappedKeys, environment, getPredicate());

        for (Sorter sorter : getSorters()) {
            Object first = sorter.getOptions().get(0);
            if (first instanceof String) {
                addMappedKey(mappedKeys, environment, (String) first);
            }
        }

        return mappedKeys;
    }

    private void addMappedPredicate(
            Map<String, MappedKey> mappedKeys,
            DatabaseEnvironment environment,
            Predicate predicate) {

        if (predicate == null) {

        } else if (predicate instanceof CompoundPredicate) {
            for (Predicate child : ((CompoundPredicate) predicate).getChildren()) {
                addMappedPredicate(mappedKeys, environment, child);
            }

        } else if (predicate instanceof ComparisonPredicate) {
            addMappedKey(mappedKeys, environment, ((ComparisonPredicate) predicate).getKey());
        }
    }

    private void addMappedKey(
            Map<String, MappedKey> mappedKeys,
            DatabaseEnvironment environment,
            String key) {

        if (!mappedKeys.containsKey(key)) {
            mappedKeys.put(key, mapEmbeddedKey(environment, key));
        }
    }

    // --- Cloneable support ---

    @Override
    public Query<E> clone() {
        Query<E> clone = new Query<E>(group, objectClass);
        clone.setPredicate(predicate);
        clone.setSorters(sorters != null ? new ArrayList<Sorter>(sorters) : null);
        clone.setDatabase(database);
        clone.setResolveToReferenceOnly(isResolveToReferenceOnly);
        clone.setTimeout(timeout);
        clone.setOptions(options != null ? new HashMap<String, Object>(options) : null);
        return clone;
    }

    // --- HtmlObject support ---

    @Override
    public void format(HtmlWriter writer) throws IOException {
        String objectClass = getObjectClass() != null ? getObjectClass().getName() : null;

        StringBuilder codeBuilder = new StringBuilder();
        codeBuilder.append("Query");

        if (isFromAll()) {
            codeBuilder.append(".fromAll()");

        } else {
            if (objectClass != null) {
                codeBuilder.append(".from(");
                codeBuilder.append(objectClass);
                codeBuilder.append(".class)");

            } else {
                codeBuilder.append(".fromGroup(\"");
                codeBuilder.append(getGroup());
                codeBuilder.append("\")");
            }
        }

        Predicate predicate = getPredicate();
        if (predicate != null) {
            codeBuilder.append(".where(\"");
            codeBuilder.append(predicate);
            codeBuilder.append("\")");
        }

        for (Sorter sorter : getSorters()) {
            codeBuilder.append(".sort(\"");
            codeBuilder.append(sorter.getOperator());
            codeBuilder.append("\"");
            for (Object option : sorter.getOptions()) {
                codeBuilder.append(", ");
                if (option instanceof String) {
                    codeBuilder.append("\"");
                    codeBuilder.append(((String) option).replaceAll("\"", "\\\""));
                    codeBuilder.append("\"");
                } else {
                    codeBuilder.append(option);
                }
            }
            codeBuilder.append(")");
        }

        List<String> fields = getFields();
        if (fields != null) {
            codeBuilder.append(".fields(");
            for (String field : fields) {
                codeBuilder.append("\"");
                codeBuilder.append(field);
                codeBuilder.append("\", ");
            }
            if (!fields.isEmpty()) {
                codeBuilder.setLength(codeBuilder.length() - 2);
            }
            codeBuilder.append(")");
        }

        writer.start("span", "class", "dari-query");
            String code = codeBuilder.toString();
            writer.html(code);

            // Use a form instead of a link if the URL will be too long.
            if (code.length() > 2000) {
                writer.start("form",
                        "method", "post",
                        "action", "/_debug/code",
                        "target", "query");
                    writer.tag("input", "type", "hidden", "name", "query", "value", code);
                    writer.tag("input", "type", "hidden", "name", "objectClass", "value", objectClass);
                    writer.tag("input", "class", "btn", "type", "submit", "value", "Execute");
                writer.end();

            } else {
                writer.html(" (");
                    writer.start("a",
                            "href", StringUtils.addQueryParameters("/_debug/code", "query", code, "objectClass", objectClass),
                            "target", "query");
                        writer.html("Execute");
                    writer.end();
                writer.html(")");
            }

        writer.end();
    }

    // --- Object support ---

    @Override
    public boolean equals(Object other) {

        if (this == other) {
            return true;

        } else if (other instanceof Query) {
            Query<?> otherQuery = (Query<?>) other;
            return ObjectUtils.equals(group, otherQuery.group) &&
                    ObjectUtils.equals(objectClass, otherQuery.objectClass) &&
                    ObjectUtils.equals(predicate, otherQuery.predicate) &&
                    ObjectUtils.equals(getSorters(), otherQuery.getSorters()) &&
                    ObjectUtils.equals(getDatabase(), otherQuery.getDatabase()) &&
                    isResolveToReferenceOnly == otherQuery.isResolveToReferenceOnly &&
                    ObjectUtils.equals(timeout, otherQuery.timeout);

        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return ObjectUtils.hashCode(
                getGroup(),
                getObjectClass(),
                getPredicate(),
                getSorters(),
                getDatabase(),
                isResolveToReferenceOnly(),
                getTimeout());
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("{group=").append(group);
        stringBuilder.append(", objectClass=").append(objectClass);
        stringBuilder.append(", predicate=").append(predicate);
        stringBuilder.append(", sorters=").append(sorters);
        stringBuilder.append(", database=").append(database);
        stringBuilder.append(", isResolveToReferenceOnly=").append(isResolveToReferenceOnly);
        stringBuilder.append(", timeout=").append(timeout);
        stringBuilder.append(", options=").append(options);
        stringBuilder.append("}");
        return stringBuilder.toString();
    }

    // --- Database bridge ---

    /**
     * Returns a count of all objects matching this query in a
     * {@linkplain #getDatabase database}.
     */
    public long count() {
        return getDatabase().readCount(this);
    }

    /** Deletes all objects matching this query. */
    public void deleteAll() {
        getDatabase().deleteByQuery(this);
    }

    public void deleteAllImmediately() {
        Database database = getDatabase();
        database.beginIsolatedWrites();
        try {
            database.deleteByQuery(this);
            database.commitWrites();
        } finally {
            database.endWrites();
        }
    }

    /**
     * Returns the first object matching this query in a {@linkplain
     * #getDatabase database}.
     */
    public E first() {
        return getDatabase().readFirst(this);
    }

    /**
     * Returns all objects matching the given {@code query} grouped by
     * the values of the given {@code fields} in a {@linkplain
     * #getDatabase database}.
     */
    public List<Grouping<E>> groupBy(String... fields) {
        return getDatabase().readAllGrouped(this, fields);
    }

    /**
     * Returns all objects matching the given {@code query} grouped by
     * the values of the given {@code fields} in a {@linkplain
     * #getDatabase database}.
     */
    public PaginatedResult<Grouping<E>> groupByPartial(long offset, int limit, String... fields) {
        return getDatabase().readPartialGrouped(this, offset, limit, fields);
    }

    /**
     * Returns the date when the objects matching this query were
     * last updated in a {@linkplain #getDatabase database}.
     */
    public Date lastUpdate() {
        return getDatabase().readLastUpdate(this);
    }

    /**
     * Returns a partial list of all objects matching this query
     * within the range of the given {@code offset} and {@code limit}
     * in a {@linkplain #getDatabase database}.
     */
    public PaginatedResult<E> select(long offset, int limit) {
        return getDatabase().readPartial(this, offset, limit);
    }

    /**
     * Returns a list of all objects matching this query in a
     * {@linkplain #getDatabase database}.
     */
    public List<E> selectAll() {
        return getDatabase().readAll(this);
    }

    // --- Database.Static bridge ---

    /**
     * Finds an object of the given {@code type} matching the given
     * {@code id} in the {@linkplain Database.Static#getDefault
     * default database}.
     */
    public static <T> T findById(Class<T> type, UUID id) {
        return Database.Static.findById(Database.Static.getDefault(), type, id);
    }

    /**
     * Finds an unique object of the given {@code type} matching the
     * given {@code field} and {@code value} in the {@linkplain
     * Database.Static#getDefault default database}.
     */
    public static <T> T findUnique(Class<T> type, String key, String value) {
        return Database.Static.findUnique(Database.Static.getDefault(), type, key, value);
    }

    /**
     * Returns an iterable of all objects matching this query in a
     * {@linkplain #getDatabase database}.
     */
    public Iterable<E> iterable(int fetchSize) {
        return getDatabase().readIterable(this, fetchSize);
    }

    /** Static utility methods. */
    public static final class Static {

        private static final Map<String, String> KEY_ALIASES; static {
            Map<String, String> m = new HashMap<String, String>();
            m.put("*", ANY_KEY);
            m.put("id", ID_KEY);
            m.put("typeId", TYPE_KEY);
            m.put("_fields", ANY_KEY);
            KEY_ALIASES = m;
        }

        private Static() {
        }

        /** Returns the canonical form of the given {@code key}. */
        public static String getCanonicalKey(String key) {
            String canonicalKey = KEY_ALIASES.get(key);
            return canonicalKey != null ? canonicalKey : key;
        }

        /**
         * Returns the query that caused the given {@code object}
         * to be created.
         *
         * @return May be {@code null} if the information isn't available.
         */
        public static Query<?> getCreator(Object object) {
            return object != null ? (Query<?>) State.getInstance(object).getExtras().get(CREATOR_EXTRA) : null;
        }
    }

    // --- Deprecated ---

    /** @deprecated Use {@link #fromType} instead. */
    @Deprecated
    public static Query<Object> from(UUID typeId) {
        return fromType(ObjectType.getInstance(typeId));
    }

    /** @deprecated Use {@link #sortRelevant(int, Predicate)} instead. */
    @Deprecated
    public Query<E> sortRelevant(int weight, Predicate predicate) {
        return sortRelevant((double) weight, predicate);
    }

    /** @deprecated Use {@link #sortRelevant(int, String, Object...)} instead. */
    @Deprecated
    public Query<E> sortRelevant(int weight, String predicateString, Object... parameters) {
        return sortRelevant((double) weight, PredicateParser.Static.parse(predicateString, parameters));
    }

    /** @deprecated Use {@link #resolveToReferenceOnly} instead. */
    @Deprecated
    public Query<E> resolveReferenceOnly() {
        return resolveToReferenceOnly();
    }

    public Query<E> facetQuery(Query<?> query) {
        this.facetedQuery = query;
        return this;
    }

    public Query<?> getFacetQuery() {
        return this.facetedQuery;
    }

    public Query<E> facetedField(String fieldName, Object value) {
        this.facetedFields.put(fieldName, value);
        return this;
    }

    public Map<String, Object> getFacetedFields() {
        return this.facetedFields;
    }

    public HashSet<String> getExtraSourceColumns() {
        return this.extraSourceColumns;
    }

    /** @deprecated Use {@link #delete} instead. */
    @Deprecated
    public void delete() {
        deleteAll();
    }

    /** @deprecated Use {@link #selectAll} instead. */
    @Deprecated
    public List<E> select() {
        return selectAll();
    }

    /** @deprecated Use {@link #groupBy} instead. */
    @Deprecated
    public Map<Object, Long> countBy(String key) {
        return getDatabase().readGroupedCount(this, key);
    }

    /** @deprecated Use {@link #mapEmbeddedKey} instead. */
    @Deprecated
    public MappedKey mapKey(DatabaseEnvironment environment, String key) {
        return mapEmbeddedKey(environment, key);
    }

    /** @deprecated Use {@link #mapEmbeddedKeys} instead. */
    @Deprecated
    public Map<String, MappedKey> mapKeys(DatabaseEnvironment environment) {
        return mapEmbeddedKeys(environment);
    }
}
