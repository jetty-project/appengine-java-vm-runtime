/**
 * Copyright 2015 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.apphosting.tests.usercode.testservlets.security;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.AccessibleObject;

/**
 * Tests reflection in dev_appserver.
 *
 */
public class TestReflection implements Runnable {

  // NB(tobyr) Much of this code is copied from HackTest. We need to find a
  // way to unify these tests. Meanwhile keep the tests in sync as much as
  // is reasonable.

  private static class Reflectee {

    public static final String STRING = "string";

    public static final int INT = 10;

    @SuppressWarnings({"UnusedDeclaration"})
    private final Object privateField = null;

    final Object packageField = null;

    protected final Object protectedField = null;

    // Different access constructors for reflection access tests.

    @SuppressWarnings({"UnusedDeclaration"})
    public Reflectee(String s) {}

    Reflectee() {}

    @SuppressWarnings({"UnusedDeclaration"})
    protected Reflectee(float f) {}

    @SuppressWarnings({"UnusedDeclaration"})
    private Reflectee(int unused) {}

    public int publicGetInt() {
      return INT;
    }

    @SuppressWarnings({"UnusedDeclaration"})
    private String privateGetString() {
      return STRING;
    }
  }

  private static class Reflectee2 {
    // Include a field of each basic type.
    public boolean booleanField;
    public byte byteField;
    public char charField;
    public short shortField;
    public int intField;
    public long longField;
    public float floatField;
    public double doubleField;
    public Object objectField;
  }

  private static class Reflectee3Base {
    public static class PublicInherited {}
  }

  private static class Reflectee3 extends Reflectee3Base {
    public static Class constructorLocalClass;
    public static Class constructorAnonymousClass;

    static {
      // make sure the static members are assigned
      new Reflectee3();
    }

    @SuppressWarnings({"InstantiatingObjectToGetClassObject"})
    private Reflectee3() {
      class ConstructorNested {}
      constructorLocalClass = ConstructorNested.class;
      constructorAnonymousClass = new Object() {}.getClass();
    }

    public static class PublicNested {}

    static class PackagedNested {}

    private static class PrivateNested {
      private static class DoubleNested {}
    }

    private static Class<?> getMethodLocalClass() {
      class MethodNestedLocal {}
      return MethodNestedLocal.class;
    };

    @SuppressWarnings({"InstantiatingObjectToGetClassObject"})
    private static Class<?> getMethodAnonymousClass() {
      return new Object() {}.getClass();
    };
  }

  public void run() {
    try {
      testReflectAccessSelf();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public void testReflectAccessSelf()
      throws NoSuchFieldException, NoSuchMethodException, IllegalAccessException,
          InstantiationException {

    // Try reflecting on self
    Class<Reflectee> klass = Reflectee.class;
    Constructor[] declaredConstructors = klass.getDeclaredConstructors();
    assertTrue(declaredConstructors.length == 4);
    Method[] declaredMethods = klass.getDeclaredMethods();
    assertTrue(declaredMethods.length == 2);
    Field[] declaredFields = klass.getDeclaredFields();
    assertTrue(declaredFields.length == 5);
    Constructor[] publicConstructors = klass.getConstructors();
    assertTrue(publicConstructors.length == 1);
    klass.newInstance();

    Method[] publicMethods = klass.getMethods();
    // 1 public method + 9 public methods inherited from java.lang.Object
    assertTrue(publicMethods.length == 10);
    Field[] publicFields = klass.getFields();
    assertTrue(publicFields.length == 2);
    Reflectee reflectee = new Reflectee();

    // Try calling a public method via reflection
    try {
      Method m = klass.getDeclaredMethod("publicGetInt");
      m.setAccessible(true);
      assertEquals(Integer.valueOf(Reflectee.INT), m.invoke(reflectee));
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    // Try calling a private method without setAccessible.
    try {
      Method m = klass.getDeclaredMethod("privateGetString");
      assertEquals(Reflectee.STRING, m.invoke(reflectee));
      fail("Should not have been able to call private method.");
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      // expected
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    }

    // Try calling a private method with setAccessible.
    try {
      Method m = klass.getDeclaredMethod("privateGetString");
      m.setAccessible(true);
      AccessibleObject.setAccessible(new AccessibleObject[] {m}, true);
      assertEquals(Reflectee.STRING, m.invoke(reflectee));
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    }

    // Try calling a private constructor without setAccessible.
    try {
      Constructor c = klass.getDeclaredConstructor(int.class);
      c.newInstance(42);
      fail("Should not have been able to call private constructor.");
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      // expected
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    // Try calling a package constructor without setAccessible.
    try {
      Constructor c = klass.getDeclaredConstructor();
      c.newInstance();
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    // Try calling a protected constructor without setAccessible.
    try {
      Constructor c = klass.getDeclaredConstructor(float.class);
      c.newInstance(1.0f);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    // Try calling a private constructor with setAccessible.
    try {
      Constructor c = klass.getDeclaredConstructor(Integer.TYPE);
      c.setAccessible(true);
      c.newInstance(new Integer(42));
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    } catch (InvocationTargetException e) {
      throw new RuntimeException(e);
    }

    // Access fields of all basic types.
    Reflectee2 obj = new Reflectee2();
    Class klass2 = Reflectee2.class;
    Field f;
    f = klass2.getField("booleanField");
    f.get(obj);
    f.getBoolean(obj);
    f = klass2.getField("byteField");
    f.get(obj);
    f.getByte(obj);
    f = klass2.getField("charField");
    f.get(obj);
    f.getChar(obj);
    f = klass2.getField("shortField");
    f.get(obj);
    f.getShort(obj);
    f = klass2.getField("intField");
    f.get(obj);
    f.getInt(obj);
    f = klass2.getField("longField");
    f.get(obj);
    f.getLong(obj);
    f = klass2.getField("floatField");
    f.get(obj);
    f.getFloat(obj);
    f = klass2.getField("doubleField");
    f.get(obj);
    f.getDouble(obj);
    f = klass2.getField("objectField");
    f.get(obj);

    Class<?>[] publicClasses = Reflectee3.class.getClasses();
    // 1 public declared, and 1 inherited
    assertEquals(2, publicClasses.length);

    // Just nested and inner classes - not anonymous or local
    Class<?>[] declaredClasses = Reflectee3.class.getDeclaredClasses();
    assertEquals(3, declaredClasses.length);

    assertNotNull(Reflectee3.constructorAnonymousClass.getEnclosingConstructor());
    assertNotNull(Reflectee3.constructorLocalClass.getEnclosingConstructor());
    assertNotNull(Reflectee3.getMethodAnonymousClass().getEnclosingMethod());
    assertNotNull(Reflectee3.getMethodLocalClass().getEnclosingMethod());

    assertNotNull(Reflectee3.PrivateNested.DoubleNested.class);
  }

  private void assertNotNull(Object obj) {
    if (obj == null) {
      throw new RuntimeException("Not null: " + obj);
    }
  }

  private void fail(String msg) {
    throw new RuntimeException(msg);
  }

  private void assertEquals(Object o1, Object o2) {
    if (o1 == null) {
      if (o1 != o2) {
        throw new RuntimeException(o1 + " != " + o2);
      }
    } else {
      if (!o1.equals(o2)) {
        throw new RuntimeException(o1 + " != " + o2);
      }
    }
  }

  private void assertTrue(boolean b) {
    if (!b) {
      throw new RuntimeException("Assertion failed.");
    }
  }
}
