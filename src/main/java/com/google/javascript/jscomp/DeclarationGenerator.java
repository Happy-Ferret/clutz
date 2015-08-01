package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.jstype.FunctionType;
import com.google.javascript.rhino.jstype.JSType;
import com.google.javascript.rhino.jstype.ObjectType;
import com.google.javascript.rhino.jstype.UnionType;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * A tool that generates {@code .d.ts} declarations from a Google Closure JavaScript program.
 */
public class DeclarationGenerator {

  private static final Logger logger = Logger.getLogger(DeclarationGenerator.class.getName());

  private StringWriter out = new StringWriter();
  private final boolean parseExterns;

  DeclarationGenerator(boolean parseExterns) {
    this.parseExterns = parseExterns;
  }

  String generateDeclarations(String sourceContents) {
    Compiler compiler = new Compiler();
    final CompilerOptions options = new CompilerOptions();
    options.setCheckGlobalNamesLevel(CheckLevel.ERROR);
    options.setCheckGlobalThisLevel(CheckLevel.ERROR);
    options.setCheckTypes(true);
    options.setInferTypes(true);
    options.setIdeMode(true); // So that we can query types after compilation.
    options.setErrorHandler(new ErrorHandler() {
      @Override
      public void report(CheckLevel level, JSError error) {
        throw new AssertionError(error.toString());
      }
    });
    compiler.setPassConfig(new DefaultPassConfig(options));
    // Don't print anything, throw later below.
    compiler.setErrorManager(new BasicErrorManager() {
      @Override
      public void println(CheckLevel level, JSError error) {}

      @Override
      protected void printSummary() {}
    });

    SourceFile sourceFile = SourceFile.fromCode("test.js", sourceContents);
    Result compilationResult =
        compiler.compile(getExterns(), Collections.singletonList(sourceFile), options);
    if (compiler.hasErrors()) {
      throw new AssertionError("Compile failed: " + Arrays.toString(compilationResult.errors));
    }

    Node root = compiler.getJsRoot();
    CollectGoogProvides collector = new CollectGoogProvides();
    NodeTraversal.traverse(compiler, root, collector);
    logger.fine("Generating declarations for " + collector.googProvides);

    out = new StringWriter();
    TypedScope topScope = compiler.getTopScope();
    for (String provide : collector.googProvides) {
      emitNoSpace("declare module 'goog:");
      emitNoSpace(provide);
      emitNoSpace("' {");
      indent();
      emitBreak();
      TypedVar symbol = topScope.getOwnSlot(provide);
      checkArgument(symbol != null, "goog.provide not defined: %s", provide);
      if (symbol.getType() != null) {
        walkScope(symbol);
      } else {
        // JSCompiler treats "foo.x" as one variable name, so collect all provides that start with
        // $provide + ".".
        String prefix = provide + ".";
        for (TypedVar other : topScope.getAllSymbols()) {
          if (other.getName().startsWith(prefix) && other.getType() != null) {
            walkScope(other);
          }
        }
      }
      unindent();
      emit("}");
      emitBreak();
    }
    checkState(indent == 0, "indent must be zero after printing, but is %s", indent);
    return out.toString();
  }

  private List<SourceFile> getExterns() {
    if (!parseExterns) {
      return ImmutableList.of();
    }
    try {
      return CommandLineRunner.getDefaultExterns();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private int indent = 0;
  private boolean startOfLine = true;

  private void indent() {
    indent++;
  }

  private void unindent() {
    indent--;
    checkState(indent >= 0, "indentation level below zero");
  }

  private void emitNoSpace(String str) {
    maybeEmitIndent();
    out.write(str);
  }

  private void emit(String str) {
    if (!maybeEmitIndent()) {
      out.write(" ");
    }
    out.write(str);
  }

  private boolean maybeEmitIndent() {
    if (!startOfLine) {
      return false;
    }
    for (int i = 0; i < indent; i++) {
      out.write("  ");
    }
    startOfLine = false;
    return true;
  }

  private void emitBreak() {
    out.write("\n");
    startOfLine = true;
  }

  private void walkScope(TypedVar symbol) {
    JSType type = symbol.getType();
    if (type.isFunctionType()) {
      emit("export default");
      FunctionType ftype = (FunctionType) type;
      if (type.isOrdinaryFunction()) {
        emit("function");
        visitFunctionDeclaration(ftype);
        return;
      }
      if (type.isConstructor()) {
        emit("class");
      } else if (type.isInterface()) {
        emit("interface");
      }
      visitObjectType(ftype, ftype.getPrototype());
    } else {
      emit("var");
      emit(getUnqualifiedName(symbol));
      visitTypeDeclaration(type);
      emit(";");
      emitBreak();
    }
  }

  private String getUnqualifiedName(TypedVar symbol) {
    String qualifiedName = symbol.getName();
    int dotIdx = qualifiedName.lastIndexOf('.');
    if (dotIdx == -1) {
      return qualifiedName;
    }
    return qualifiedName.substring(dotIdx + 1, qualifiedName.length());
  }

  private void visitTypeDeclaration(JSType type) {
    if (type != null) {
      emit(":");
      visitType(type);
    }
  }

  private void visitType(JSType type) {
    // See also JsdocToEs6TypedConverter in the Closure code base. This code is implementing the
    // same algorithm starting from JSType nodes (as opposed to JSDocInfo), and directly generating
    // textual output. Otherwise both algorithms should produce the same output.
    if (type.isString()) {
      emit("string");
    } else if (type.isNumber()) {
      emit("number");
    } else if (type.isBooleanValueType()) {
      emit("boolean");
    } else if (type.isUnionType()) {
      visitUnionType((UnionType) type);
    } else if (type.isNominalType()) {
      ObjectType ot = (ObjectType) type;
      this.emit(ot.getReferenceName());
    } else {
      throw new IllegalArgumentException("Unsupported type: " + type);
    }
  }

  private void visitUnionType(UnionType ut) {
    Collection<JSType> alts = Collections2.filter(ut.getAlternates(), new Predicate<JSType>() {
      @Override
      public boolean apply(JSType input) {
        // Skip - JSCompiler does not have explicit null types.
        return !input.isNullable();
      }
    });
    if (alts.size() == 1) {
      visitType(alts.iterator().next());
      return;
    }
    this.emit("(");
    Iterator<JSType> it = alts.iterator();
    while (it.hasNext()) {
      visitType(it.next());
      if (it.hasNext()) {
        this.emit("|");
      }
    }
    this.emit(")");
  }

  private void visitObjectType(ObjectType type, ObjectType prototype) {
    emit("{");
    indent();
    emitBreak();
    // Fields.
    JSType instanceType = type.getTypeOfThis();
    if (!instanceType.isObject()) {
      throw new IllegalArgumentException("expected an ObjectType for this, but got " + instanceType
          + " which is a " + instanceType.getClass().getSimpleName());
    }
    visitProperties((ObjectType) instanceType, false);
    // Methods.
    visitProperties(prototype, false);
    // Statics.
    visitProperties(type, true);
    unindent();
    emit("}");
    emitBreak();
  }

  private void visitProperties(ObjectType objType, boolean isStatic) {
    for (String propName : objType.getOwnPropertyNames()) {
      if ("prototype".equals(propName)) {
        continue;
      }
      if (isStatic) {
        emit("static");
      }
      emit(propName);
      JSType propertyType = objType.getPropertyType(propName);
      if (propertyType.isFunctionType()) {
        visitFunctionDeclaration((FunctionType) propertyType);
      } else {
        visitTypeDeclaration(propertyType);
      }
      emit(";");
      emitBreak();
    }
  }

  private void visitFunctionDeclaration(FunctionType ftype) {
    emit("(");
    Iterator<Node> parameters = ftype.getParameters().iterator();
    char pName = 'a'; // let's hope for no more than 26 parameters...
    while (parameters.hasNext()) {
      Node param = parameters.next();
      if (param.isVarArgs()) {
        emit("...");
      }
      emitNoSpace("" + pName++);
      if (param.isOptionalArg()) {
        emit("?");
      }
      visitTypeDeclaration(param.getJSType());
      if (parameters.hasNext()) {
        emit(", ");
      }
    }
    emit(")");
    visitTypeDeclaration(ftype.getReturnType());
  }

  static class CollectGoogProvides implements NodeTraversal.Callback {

    private HashSet<String> googProvides = new HashSet<>();

    @Override
    public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
      return true;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isCall() && n.getFirstChild().matchesQualifiedName("goog.provide")) {
        Node nsParam = n.getFirstChild().getNext();
        if (nsParam == null || !nsParam.isString()) {
          return;
        }
        googProvides.add(nsParam.getString());
      }
    }

  }
}