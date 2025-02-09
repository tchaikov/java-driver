/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datastax.driver.core;

import com.datastax.driver.core.exceptions.DriverInternalError;
import com.datastax.driver.core.utils.Bytes;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Parse data types from schema tables, for Cassandra 3.0 and above.
 * In these versions, data types appear as class names, like "org.apache.cassandra.db.marshal.AsciiType"
 * or "org.apache.cassandra.db.marshal.TupleType(org.apache.cassandra.db.marshal.Int32Type,org.apache.cassandra.db.marshal.Int32Type)".
 *
 * This is modified (and simplified) from Cassandra's TypeParser class to suit
 * our needs. In particular it's not very efficient, but it doesn't really matter
 * since it's rarely used and never in a critical path.
 *
 * Note that those methods all throw DriverInternalError when there is a parsing
 * problem because in theory we'll only parse class names coming from Cassandra and
 * so there shouldn't be anything wrong with them.
 */
class DataTypeClassNameParser {
  private static final Logger logger = LoggerFactory.getLogger(DataTypeClassNameParser.class);

  private static final String REVERSED_TYPE = "org.apache.cassandra.db.marshal.ReversedType";
  private static final String FROZEN_TYPE = "org.apache.cassandra.db.marshal.FrozenType";
  private static final String COMPOSITE_TYPE = "org.apache.cassandra.db.marshal.CompositeType";
  private static final String COLLECTION_TYPE =
      "org.apache.cassandra.db.marshal.ColumnToCollectionType";
  private static final String LIST_TYPE = "org.apache.cassandra.db.marshal.ListType";
  private static final String SET_TYPE = "org.apache.cassandra.db.marshal.SetType";
  private static final String MAP_TYPE = "org.apache.cassandra.db.marshal.MapType";
  private static final String UDT_TYPE = "org.apache.cassandra.db.marshal.UserType";
  private static final String TUPLE_TYPE = "org.apache.cassandra.db.marshal.TupleType";
  private static final String DURATION_TYPE = "org.apache.cassandra.db.marshal.DurationType";

  private static ImmutableMap<String, DataType> cassTypeToDataType =
      new ImmutableMap.Builder<String, DataType>()
          .put("org.apache.cassandra.db.marshal.AsciiType", DataType.ascii())
          .put("org.apache.cassandra.db.marshal.LongType", DataType.bigint())
          .put("org.apache.cassandra.db.marshal.BytesType", DataType.blob())
          .put("org.apache.cassandra.db.marshal.BooleanType", DataType.cboolean())
          .put("org.apache.cassandra.db.marshal.CounterColumnType", DataType.counter())
          .put("org.apache.cassandra.db.marshal.DecimalType", DataType.decimal())
          .put("org.apache.cassandra.db.marshal.DoubleType", DataType.cdouble())
          .put("org.apache.cassandra.db.marshal.FloatType", DataType.cfloat())
          .put("org.apache.cassandra.db.marshal.InetAddressType", DataType.inet())
          .put("org.apache.cassandra.db.marshal.Int32Type", DataType.cint())
          .put("org.apache.cassandra.db.marshal.UTF8Type", DataType.text())
          .put("org.apache.cassandra.db.marshal.TimestampType", DataType.timestamp())
          .put("org.apache.cassandra.db.marshal.SimpleDateType", DataType.date())
          .put("org.apache.cassandra.db.marshal.TimeType", DataType.time())
          .put("org.apache.cassandra.db.marshal.UUIDType", DataType.uuid())
          .put("org.apache.cassandra.db.marshal.IntegerType", DataType.varint())
          .put("org.apache.cassandra.db.marshal.TimeUUIDType", DataType.timeuuid())
          .put("org.apache.cassandra.db.marshal.ByteType", DataType.tinyint())
          .put("org.apache.cassandra.db.marshal.ShortType", DataType.smallint())
          .put(DURATION_TYPE, DataType.duration())
          .build();

  static DataType parseOne(
      String className, ProtocolVersion protocolVersion, CodecRegistry codecRegistry) {
    boolean frozen = false;
    if (isReversed(className)) {
      // Just skip the ReversedType part, we don't care
      className = getNestedClassName(className);
    } else if (isFrozen(className)) {
      frozen = true;
      className = getNestedClassName(className);
    }

    Parser parser = new Parser(className, 0);
    String next = parser.parseNextName();

    if (next.startsWith(LIST_TYPE))
      return DataType.list(
          parseOne(parser.getTypeParameters().get(0), protocolVersion, codecRegistry), frozen);

    if (next.startsWith(SET_TYPE))
      return DataType.set(
          parseOne(parser.getTypeParameters().get(0), protocolVersion, codecRegistry), frozen);

    if (next.startsWith(MAP_TYPE)) {
      List<String> params = parser.getTypeParameters();
      return DataType.map(
          parseOne(params.get(0), protocolVersion, codecRegistry),
          parseOne(params.get(1), protocolVersion, codecRegistry),
          frozen);
    }

    if (frozen)
      logger.warn(
          "Got o.a.c.db.marshal.FrozenType for something else than a collection, "
              + "this driver version might be too old for your version of Cassandra");

    if (isUserType(next)) {
      ++parser.idx; // skipping '('

      String keyspace = parser.readOne();
      parser.skipBlankAndComma();
      String typeName =
          TypeCodec.varchar()
              .deserialize(Bytes.fromHexString("0x" + parser.readOne()), protocolVersion);
      parser.skipBlankAndComma();
      Map<String, String> rawFields = parser.getNameAndTypeParameters(protocolVersion);
      List<UserType.Field> fields = new ArrayList<UserType.Field>(rawFields.size());
      for (Map.Entry<String, String> entry : rawFields.entrySet())
        fields.add(
            new UserType.Field(
                entry.getKey(), parseOne(entry.getValue(), protocolVersion, codecRegistry)));
      // create a frozen UserType since C* 2.x UDTs are always frozen.
      return new UserType(keyspace, typeName, true, fields, protocolVersion, codecRegistry);
    }

    if (isTupleType(next)) {
      List<String> rawTypes = parser.getTypeParameters();
      List<DataType> types = new ArrayList<DataType>(rawTypes.size());
      for (String rawType : rawTypes) {
        types.add(parseOne(rawType, protocolVersion, codecRegistry));
      }
      return new TupleType(types, protocolVersion, codecRegistry);
    }

    DataType type = cassTypeToDataType.get(next);
    return type == null ? DataType.custom(className) : type;
  }

  public static boolean isReversed(String className) {
    return className.startsWith(REVERSED_TYPE);
  }

  public static boolean isFrozen(String className) {
    return className.startsWith(FROZEN_TYPE);
  }

  private static String getNestedClassName(String className) {
    Parser p = new Parser(className, 0);
    p.parseNextName();
    List<String> l = p.getTypeParameters();
    if (l.size() != 1) throw new IllegalStateException();
    className = l.get(0);
    return className;
  }

  public static boolean isUserType(String className) {
    return className.startsWith(UDT_TYPE);
  }

  public static boolean isTupleType(String className) {
    return className.startsWith(TUPLE_TYPE);
  }

  private static boolean isComposite(String className) {
    return className.startsWith(COMPOSITE_TYPE);
  }

  private static boolean isCollection(String className) {
    return className.startsWith(COLLECTION_TYPE);
  }

  public static boolean isDuration(String className) {
    return className.equals(DURATION_TYPE);
  }

  static ParseResult parseWithComposite(
      String className, ProtocolVersion protocolVersion, CodecRegistry codecRegistry) {
    Parser parser = new Parser(className, 0);

    String next = parser.parseNextName();
    if (!isComposite(next))
      return new ParseResult(parseOne(className, protocolVersion, codecRegistry), isReversed(next));

    List<String> subClassNames = parser.getTypeParameters();
    int count = subClassNames.size();
    String last = subClassNames.get(count - 1);
    Map<String, DataType> collections = new HashMap<String, DataType>();
    if (isCollection(last)) {
      count--;
      Parser collectionParser = new Parser(last, 0);
      collectionParser.parseNextName(); // skips columnToCollectionType
      Map<String, String> params = collectionParser.getCollectionsParameters(protocolVersion);
      for (Map.Entry<String, String> entry : params.entrySet())
        collections.put(entry.getKey(), parseOne(entry.getValue(), protocolVersion, codecRegistry));
    }

    List<DataType> types = new ArrayList<DataType>(count);
    List<Boolean> reversed = new ArrayList<Boolean>(count);
    for (int i = 0; i < count; i++) {
      types.add(parseOne(subClassNames.get(i), protocolVersion, codecRegistry));
      reversed.add(isReversed(subClassNames.get(i)));
    }

    return new ParseResult(true, types, reversed, collections);
  }

  static class ParseResult {
    public final boolean isComposite;
    public final List<DataType> types;
    public final List<Boolean> reversed;
    public final Map<String, DataType> collections;

    private ParseResult(DataType type, boolean reversed) {
      this(
          false,
          Collections.<DataType>singletonList(type),
          Collections.<Boolean>singletonList(reversed),
          Collections.<String, DataType>emptyMap());
    }

    private ParseResult(
        boolean isComposite,
        List<DataType> types,
        List<Boolean> reversed,
        Map<String, DataType> collections) {
      this.isComposite = isComposite;
      this.types = types;
      this.reversed = reversed;
      this.collections = collections;
    }
  }

  private static class Parser {

    private final String str;
    private int idx;

    private Parser(String str, int idx) {
      this.str = str;
      this.idx = idx;
    }

    public String parseNextName() {
      skipBlank();
      return readNextIdentifier();
    }

    public String readOne() {
      String name = parseNextName();
      String args = readRawArguments();
      return name + args;
    }

    // Assumes we have just read a class name and read it's potential arguments
    // blindly. I.e. it assume that either parsing is done or that we're on a '('
    // and this reads everything up until the corresponding closing ')'. It
    // returns everything read, including the enclosing parenthesis.
    private String readRawArguments() {
      skipBlank();

      if (isEOS() || str.charAt(idx) == ')' || str.charAt(idx) == ',') return "";

      if (str.charAt(idx) != '(')
        throw new IllegalStateException(
            String.format(
                "Expecting char %d of %s to be '(' but '%c' found", idx, str, str.charAt(idx)));

      int i = idx;
      int open = 1;
      while (open > 0) {
        ++idx;

        if (isEOS()) throw new IllegalStateException("Non closed parenthesis");

        if (str.charAt(idx) == '(') {
          open++;
        } else if (str.charAt(idx) == ')') {
          open--;
        }
      }
      // we've stopped at the last closing ')' so move past that
      ++idx;
      return str.substring(i, idx);
    }

    public List<String> getTypeParameters() {
      List<String> list = new ArrayList<String>();

      if (isEOS()) return list;

      if (str.charAt(idx) != '(') throw new IllegalStateException();

      ++idx; // skipping '('

      while (skipBlankAndComma()) {
        if (str.charAt(idx) == ')') {
          ++idx;
          return list;
        }

        try {
          list.add(readOne());
        } catch (DriverInternalError e) {
          throw new DriverInternalError(
              String.format("Exception while parsing '%s' around char %d", str, idx), e);
        }
      }
      throw new DriverInternalError(
          String.format(
              "Syntax error parsing '%s' at char %d: unexpected end of string", str, idx));
    }

    public Map<String, String> getCollectionsParameters(ProtocolVersion protocolVersion) {
      if (isEOS()) return Collections.<String, String>emptyMap();

      if (str.charAt(idx) != '(') throw new IllegalStateException();

      ++idx; // skipping '('

      return getNameAndTypeParameters(protocolVersion);
    }

    // Must be at the start of the first parameter to read
    public Map<String, String> getNameAndTypeParameters(ProtocolVersion protocolVersion) {
      // The order of the hashmap matters for UDT
      Map<String, String> map = new LinkedHashMap<String, String>();

      while (skipBlankAndComma()) {
        if (str.charAt(idx) == ')') {
          ++idx;
          return map;
        }

        String bbHex = readNextIdentifier();
        String name = null;
        try {
          name =
              TypeCodec.varchar().deserialize(Bytes.fromHexString("0x" + bbHex), protocolVersion);
        } catch (NumberFormatException e) {
          throwSyntaxError(e.getMessage());
        }

        skipBlank();
        if (str.charAt(idx) != ':') throwSyntaxError("expecting ':' token");

        ++idx;
        skipBlank();
        try {
          map.put(name, readOne());
        } catch (DriverInternalError e) {
          throw new DriverInternalError(
              String.format("Exception while parsing '%s' around char %d", str, idx), e);
        }
      }
      throw new DriverInternalError(
          String.format(
              "Syntax error parsing '%s' at char %d: unexpected end of string", str, idx));
    }

    private void throwSyntaxError(String msg) {
      throw new DriverInternalError(
          String.format("Syntax error parsing '%s' at char %d: %s", str, idx, msg));
    }

    private boolean isEOS() {
      return isEOS(str, idx);
    }

    private static boolean isEOS(String str, int i) {
      return i >= str.length();
    }

    private void skipBlank() {
      idx = skipBlank(str, idx);
    }

    private static int skipBlank(String str, int i) {
      while (!isEOS(str, i) && ParseUtils.isBlank(str.charAt(i))) ++i;

      return i;
    }

    // skip all blank and at best one comma, return true if there not EOS
    private boolean skipBlankAndComma() {
      boolean commaFound = false;
      while (!isEOS()) {
        int c = str.charAt(idx);
        if (c == ',') {
          if (commaFound) return true;
          else commaFound = true;
        } else if (!ParseUtils.isBlank(c)) {
          return true;
        }
        ++idx;
      }
      return false;
    }

    // left idx positioned on the character stopping the read
    public String readNextIdentifier() {
      int i = idx;
      while (!isEOS() && ParseUtils.isIdentifierChar(str.charAt(idx))) ++idx;

      return str.substring(i, idx);
    }

    @Override
    public String toString() {
      return str.substring(0, idx)
          + "["
          + (idx == str.length() ? "" : str.charAt(idx))
          + "]"
          + str.substring(idx + 1);
    }
  }
}
