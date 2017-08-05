# specql: easy PostgreSQL queries

specql makes querying PostgreSQL databases easy by introspecting tables at compile time and creating
specs for the rows and columns. Specql can then automatically do CRUD operations on the tables as
the table structure is known.

specql uses namespaced keys for all tables and columns and makes it easy to spot
errors at development time.

**TL;DR** To simple get a quick feel for what specql does, please look at the test suite:
* [database.sql](https://github.com/tatut/specql/blob/master/test/database.sql)
* [core_test.clj](https://github.com/tatut/specql/blob/master/test/specql/core_test.clj)


## Rationale

Creating a programmer friendly system to generate SQL is a hard problem. SQL is a big
language and either you end up mashing strings together or you try to encode the whole
language as data (or you make a poor SQL look-alike language which you then have to
generate by mashing strings together). Another solution is to punt on the problem
and just write SQL in resource files that can be loaded. That works well, but it
doesn't reduce the amount of boilerplate you have to write for different types of
queries.

Specql tries to solve the boilerplate problem for common cases and leave SQL where
it belongs (in the database). Specql introspects tables and provides a generic
fetch function which can query the introspected tables and return any projection
from them with any where filter. This removes the need to generate nearly identical
SQL queries for slightly different use cases.

Specql is opinionated in that it doesn't try to cover the full SQL language. For example aggregates
and any sort of reporting queries are simply not supported. With specql you write your
complex queries in the database and expose them as views which specql can then introspect
and work with. This has the added benefit that your data access queries are not coupled
to your application but instead live in the database where they belong.

## Defining database tables

Specql works with specs and an internal table info registry which are built at compile time with the
`define-tables` macro. This requires that you have a valid database connection during
compile time (which you should have anyway, for testing).

If your build process has no database available, you can use
[opentable/otj-pg-embedded](https://github.com/opentable/otj-pg-embedded) to easily
get an embedded database running.

The `define-tables` macro takes a database connection (anything
[clojure.java/jdbc](https://github.com/clojure/java.jdbc) supports) and
one or more vectors containing a table name, a keyword and optional join
configuration.

The table can be a regular database table, a composite user defined type, a view or an enum type.

```clojure
(def db (make-my-db))

(define-tables db
  ["customer" :customer/customers]
  ["order" :order/orders {:order/customer
                          (rel/has-one :order/customer-id
                                       :customer/customers
                                       :customer/id)}])
```

In the above example we define two tables "customer" and "order" with keywords
`:customer/customers` and `:order/orders` respectively. The table keywords can
be any namespaced keywords. Specql will automatically create specs for all columns
in the tables in the same namespace as the parent table keyword. So if customer
has columns "id", "name" and "address", the keywords `:customer/id`, `:customer/name`
and `:customer/address` will be registered as specs of the column types and added
to the tables information in the registry.

To avoid clashes, each table should have its own namespace. If you want to share a namespace for
example for a table and views about the same entity, make sure that the names are
consistent and have the same type in each table/view.

Specql supports JOINs and can navigate them while querying. The third element in the table
definition vector is a map of extra fields that are joined entities. The `specql.rel` namespace
has helper functions for creating join definitions. Join specifications have three
parameters: column in this table, the table to join, the column in the joined table.

Note that the joined entity keyword should be different from the id keyword. For example if you name
a foreign reference column by the name of the entity, you must call the joined
keyword something else (eg. "order" vs. "order-id"). It is more convenient to name foreign keys with
an "-id" suffix in the database so that the unsuffixed name can be used for the joined entity.

## Querying data

The `fetch` function in the `specql.core` namespace is responsible for all queries.
It takes in a database connection and a description of what to query and returns a sequence
of maps.

### Simple example

```clojure
(fetch ;; the database connection to use
       db

       ;; the table to query from
       :order/orders

       ;; what columns to return
       #{:order/id :order/price}

       ;; where the following matches
       {:order/id 1})
;; => ({:order/id 1 :order/price 666M})
```

The following shows the basic form of a fetch call. The table is given with the same
keyword that was registered in `define-tables`. The columns to retrieve is a set
of column keywords in the table. The where clause is a map where keys are columns of
the table and values to compare against.

The keys in the returned maps will those that were specified in the columns set.

### Specifying search criteria

In the previous example, the where clause was generated with direct value comparisons.
Specql also supports common SQL operators in `specql.op` namespace:

* equality/ordinality: `=`, `not=`, `<`, `<=`, `>`, `>=`
* range: `between`
* text search: `like`
* set membership: `in`
* null checks: `null?` and `not-null?`
* combination: `or` and `and`
* negation: `not`

If a where map contains an operator instead of a value, the operator is called to generate
parameters and SQL. Keys in a where map are automatically ANDed together. A key value can
combine multiple operators with `and` or `or`. Combinations can also be used for whole
maps.

```clojure

;; Fetch orders in January
(fetch db :order/orders
       #{:order/id :order/price :order/item}
       {:order/date (op/between #inst "2017-01-01T00:00:00.000-00:00"
                                #inst "2017-01-31T23:59:59.999-00:00")})

;; Fetch recent or outstanding orders
(fetch db :order/orders
       #{:order/id :order/price :order/status :order/item}
       (op/or
        {:order/status (op/in #{"processing" "shipped"})}
	{:order/date (op/> (-> 14 days ago))}))
```

Given that where queries are made up of data and  specql validates the columns and
where criteria, it is feasible to let the client tell you what to fetch and how to
filter the result set without sacrificing security. You can use PostgreSQL row level
security to define what a user can see or simply AND a security where clause to the
query.

```clojure

(def orders-view-keys #{:order/date :order/status :order/price :order/id :order/item})

(defn user-orders
  "A where clause that restricts orders to the customer's own orders."
  [{id :user/id}]
  {:order/customer-id id})

(defn my-orders [db user search-criteria]
  (fetch db :order/orders orders-view-keys
         ;; AND together application defined criteria
	 ;; and client given filters
         (op/and (user-orders user)
	         search-criteria)))
```

In the above example the application has defined the criteria that is necessary for
security and can let the client side (for example to front end view) decide how to
filter. It can have a text search or date filter, or other restriction. The backend
code does not need to be changed to accommodate new front-end needs. The above example
can be made even more generic by letting the client decide the keys to fetch
(with a possible `clojure.set/difference` call on it to restrict it).

### Joining tables

The `fetch` function can take advantage of the join definitions given when the `define-tables`
was called. Simply add a column with the form `[:thistable/field #{:joinedtable/field1 ... :joinedtable/fieldN}]`
and the table will automatically be joined and the given fields will be available
as a nested map.

```clojure
(fetch db :order/orders
       #{:order/id :order/item :order/price
         [:order/customer #{:customer/name :customer/email}]}
       {:order/id 1})
;; => ({:order/id 1 :order/item "Log from Blammo" :order/price 42.1M
        :order/customer {:customer/name "Max Syöttöpaine"
	                 :customer/email "max@example.com"}})
```

Joins can be nested so that the joined column set can also refer to a joined table
the same way.

If the join is a `has-many` the nested value is a sequence of maps instead of a single map.

## Inserting new data

Specql provides an `insert!` function that takes a database, a table to insert to and a record
the data to insert. Insertion validates that all fields marked `NOT NULL` are present.
Insert returns the same input data back with the primary key fields added.

```clojure
(insert! db :order/orders
         {:order/item "space modulator"
	  :order/price 100M
	  :order/customer-id 1})
;; => {:order/id 123 :order/item "space modulator" :order/price 100M :order/customer-id 1}
```

## Deleting data

Specql provides a `delete!` function which can be used to delete rows from a table.
Deletion takes a database connection, the table to delete from and a where map. The
where clause is generated in the same way as in `fetch`.

Delete will assert that the where clause is not empty before running the SQL delete command.
If you really need to delete all rows from a table, use PostgreSQL `TRUNCATE` command.

```clojure
(delete! db :order/orders
         {:order/state "processed"
	  :order/date (op/< #inst "1970-01-01T00:00:00.000-00:00")})
;; => number of rows deleted
```

## Update

Specql provides a basic `update!` function that can be used to set values new values.
Only direct values can be set, updating based on an operator is not supported. Update
returns the number of affected rows.

```clojure
(update! db :order/orders
         ;; new values to set
         {:order/state "delivered"}
         ;; the where clause
         {:order/id 123})
;; => 1
```

## Upsert (UPDATE or INSERT)

PostgreSQL supports an atomic UPDATE or INSERT since version 9.5. Specql provides
a function `upsert!` which takes advantage of the feature.

Upsert takes three required parameters: the database connection, the table to upsert
to and the record to upsert. By default upsert is made with a conflict target on
the primary key. To provide another set of columns to upsert on, an optional second
argument can be provided. The set of columns must have a unique index on the table.
Upsert takes an optional where record that can be used to constrain the update
by checking for values in the conflicting database row. This can be used for
security purposes.

Upsert returns the same record back on success with the primary key added (if the
primary key was not the conflict target). If a conflict occurs but the update was
not applied because the where clause did not match, `upsert!` will return `nil`.

Note that upsert will update only the fields passed in the record, any existing
fields are left unchanged.

```clojure
(upsert! db :order/orders
         ;; record to insert (or update)
         {:order/id 1 :order/state "shipped" :order/customer-id 42
	  :order/item "a fine leather jacket" :order/price 1000M}

         ;; a where clause to check that user cannot update someone
	 ;; else's orders
	 {:order/customer-id 42})
;; => {:order/id 1 :order/state "shipped"}
```

## Stored procedures

Specql also provides support for callling stored procedures as functions.
Stored procedures are defined with the `defsp` and look like regular functions.

The return value and the parameters are handled just like when querying and any
user defined types must be defined with `define-tables` before defining the
stored procedure.

For example given the following SQL stored procedure definition:
```
CREATE FUNCTION myrange (from_ INT, to_ INT) RETURNS INT[] AS $$
 -- body elided for brevity, see sprocs.sql in tests
$$ LANGUAGE plpgsql;
```

The function can be defined and called as:
```
(defsp myrange define-db)

(meta #'myrange)
;; => {:arglists ([db17659 from_ to_]),
;;     :doc
;;     "Returns an array of successive integers in the range from_ (inclusive) -- to_ (exclusive).",
;;     ...}


(myrange db 9 17)
;; => [9 10 11 12 13 14 15 16]

``

The comment (if any) placed on the stored procedure is taken as the docstring of the
function.
