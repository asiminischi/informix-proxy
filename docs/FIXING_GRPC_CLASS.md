# Fixing the Missing InformixServiceGrpc Class

## The Problem

You see this error:
```
cannot find symbol: class InformixServiceGrpc
```

## Why This Happens

The `InformixServiceGrpc` class **doesn't exist yet** - it needs to be **generated from the proto file** by Maven's protobuf plugin.

This is **normal and expected** before your first build!

---

## The Solution (3 steps)

### Step 1: Generate the gRPC Classes

Run this from the `informix-proxy/` directory:

```bash
mvn clean compile
```

This will:
1. Download all Maven dependencies (including Informix JDBC driver)
2. Run the protobuf compiler on `proto/informix.proto`
3. Generate Java classes in `target/generated-sources/`

**Expected output:**
```
[INFO] --- protobuf-maven-plugin:0.6.1:compile (default) @ informix-grpc-proxy ---
[INFO] Compiling 1 proto file(s) to /path/to/informix-proxy/target/generated-sources/protobuf/java
[INFO] --- protobuf-maven-plugin:0.6.1:compile-custom (default) @ informix-grpc-proxy ---
[INFO] Compiling 1 proto file(s) to /path/to/informix-proxy/target/generated-sources/protobuf/grpc-java
[INFO] BUILD SUCCESS
```

### Step 2: Check Generated Files

After successful build, you should see:

```bash
ls -la target/generated-sources/protobuf/grpc-java/com/informix/grpc/
```

**You should see:**
```
InformixServiceGrpc.java          ← This is what you need!
```

And in `target/generated-sources/protobuf/java/com/informix/grpc/`:
```
Informix.java                     ← Message classes
ConnectionRequest.java
ConnectionResponse.java
QueryRequest.java
QueryResponse.java
... (and many more)
```

### Step 3: Configure Your IDE

The generated classes are in `target/generated-sources/`, but your IDE needs to know about them.

#### **IntelliJ IDEA:**

1. Right-click on `target/generated-sources/protobuf/java` → **Mark Directory as** → **Generated Sources Root**
2. Right-click on `target/generated-sources/protobuf/grpc-java` → **Mark Directory as** → **Generated Sources Root**

**OR** easier:

```bash
# Let Maven configure IntelliJ
mvn idea:idea
```

Then in IntelliJ: **File** → **Invalidate Caches** → **Invalidate and Restart**

#### **Eclipse:**

1. Right-click project → **Properties**
2. **Java Build Path** → **Source** tab
3. **Add Folder** → Select:
    - `target/generated-sources/protobuf/java`
    - `target/generated-sources/protobuf/grpc-java`
4. Click **OK**

**OR** easier:

```bash
# Let Maven configure Eclipse
mvn eclipse:eclipse
```

Then in Eclipse: **Project** → **Clean** → **Clean All Projects**

#### **VS Code:**

If using VS Code with Java extensions, reload the window:

1. Press `Cmd+Shift+P` (Mac) or `Ctrl+Shift+P` (Windows/Linux)
2. Type: "Java: Clean Java Language Server Workspace"
3. Select it and reload

---

## Alternative: Generate Without Full Build

If you just want to generate the gRPC classes without compiling everything:

```bash
# Just generate proto files
mvn protobuf:compile protobuf:compile-custom
```

This is faster if you just want to check the generated code.

---

## What Gets Generated

From `proto/informix.proto`, the protobuf compiler generates:

### 1. Message Classes (`target/generated-sources/protobuf/java/`)

Each message in your proto becomes a Java class:

```java
// From: message ConnectionRequest { ... }
public class ConnectionRequest extends GeneratedMessageV3 {
    public String getHost() { ... }
    public int getPort() { ... }
    public String getDatabase() { ... }
    // ... and builder methods
}
```

### 2. Service Stub (`target/generated-sources/protobuf/grpc-java/`)

The main class you need:

```java
public final class InformixServiceGrpc {
    
    // Abstract service implementation
    public static abstract class InformixServiceImplBase 
        implements BindableService {
        
        public void connect(ConnectionRequest request,
                          StreamObserver<ConnectionResponse> responseObserver) {
            // Your implementation goes here
        }
        
        public void executeQuery(QueryRequest request,
                               StreamObserver<QueryResponse> responseObserver) {
            // Your implementation goes here
        }
        
        // ... all other RPC methods from proto
    }
    
    // Client stub (for clients to call the service)
    public static final class InformixServiceStub { ... }
    
    // Blocking client stub
    public static final class InformixServiceBlockingStub { ... }
}
```

---

## How Your InformixProxyServer.java Uses It

Your `InformixProxyServer.java` extends the generated base class:

```java
public class InformixProxyServer 
    extends InformixServiceGrpc.InformixServiceImplBase {
    //                 ↑
    //                 This class is generated from the proto file
    
    @Override
    public void connect(ConnectionRequest request,
                       StreamObserver<ConnectionResponse> responseObserver) {
        // Your implementation
    }
    
    // ... implement other methods
}
```

---

## Common Issues & Solutions

### Issue 1: "protoc not found"

**Error:**
```
Failed to execute goal org.xolstice.maven.plugins:protobuf-maven-plugin:0.6.1:compile
```

**Solution:**
The plugin downloads protoc automatically. If it fails:

```bash
# Clear Maven cache and retry
rm -rf ~/.m2/repository/com/google/protobuf
mvn clean compile
```

### Issue 2: "Package com.informix.grpc does not exist"

**Error:**
```
package com.informix.grpc does not exist
```

**Solution:**
The `package` in your proto file must match your Java package:

In `proto/informix.proto`, ensure you have:
```protobuf
option java_package = "com.informix.grpc";
option java_multiple_files = true;
```

### Issue 3: IDE still shows errors after build

**Solution:**
Force IDE to recognize generated sources:

```bash
# For IntelliJ
mvn clean compile
mvn idea:idea

# For Eclipse  
mvn clean compile
mvn eclipse:eclipse

# For VS Code
mvn clean compile
# Then: Cmd+Shift+P → "Java: Clean Java Language Server Workspace"
```

### Issue 4: Generated files disappear after `mvn clean`

**This is normal!** `mvn clean` deletes `target/` including generated sources.

**Solution:**
Always run `mvn compile` or `mvn package` after `mvn clean`.

Use combined command:
```bash
mvn clean compile   # Cleans then compiles
```

---

## Verification Checklist

After running `mvn compile`, verify:

- [ ] No compilation errors in Maven output
- [ ] `target/generated-sources/protobuf/grpc-java/com/informix/grpc/InformixServiceGrpc.java` exists
- [ ] IDE shows no errors in `InformixProxyServer.java`
- [ ] You can see `InformixServiceGrpc.InformixServiceImplBase` when you type it

---

## The Complete Build Process

Here's what happens when you run `mvn clean package`:

```
mvn clean package
    ↓
1. mvn clean
    └─ Deletes target/ directory
    ↓
2. Download dependencies
    ├─ Informix JDBC driver (from Maven Central)
    ├─ gRPC libraries
    ├─ Protobuf libraries
    └─ HikariCP
    ↓
3. protobuf:compile
    └─ Generates message classes (ConnectionRequest, etc.)
        → target/generated-sources/protobuf/java/
    ↓
4. protobuf:compile-custom
    └─ Generates gRPC service stubs (InformixServiceGrpc)
        → target/generated-sources/protobuf/grpc-java/
    ↓
5. compile
    └─ Compiles your InformixProxyServer.java
       (now it can import InformixServiceGrpc!)
    ↓
6. package
    └─ Creates informix-grpc-proxy-1.0.0.jar
       (fat JAR with all dependencies)
```

---

## Quick Commands Reference

```bash
# Generate gRPC classes only
mvn protobuf:compile protobuf:compile-custom

# Full clean build
mvn clean compile

# Create deployable JAR
mvn clean package

# Skip tests (faster)
mvn clean package -DskipTests

# Verbose output (for debugging)
mvn clean compile -X

# Configure IntelliJ
mvn idea:idea

# Configure Eclipse
mvn eclipse:eclipse
```

---

## Expected Directory Structure After Build

```
informix-proxy/
├── src/
│   └── main/
│       └── java/
│           └── com/informix/grpc/
│               └── InformixProxyServer.java  ← Your code (imports InformixServiceGrpc)
├── proto/
│   └── informix.proto                        ← Protocol definition
├── target/
│   ├── generated-sources/
│   │   └── protobuf/
│   │       ├── java/                         ← Generated message classes
│   │       │   └── com/informix/grpc/
│   │       │       ├── ConnectionRequest.java
│   │       │       ├── ConnectionResponse.java
│   │       │       └── ... (all messages)
│   │       └── grpc-java/                    ← Generated service stubs
│   │           └── com/informix/grpc/
│   │               └── InformixServiceGrpc.java  ← This is what you need!
│   ├── classes/                              ← Compiled .class files
│   └── informix-grpc-proxy-1.0.0.jar         ← Final JAR
└── pom.xml
```

---

## TL;DR - Quick Fix

```bash
# Run this from informix-proxy/ directory
mvn clean compile

# If using IntelliJ
mvn idea:idea

# If using Eclipse
mvn eclipse:eclipse

# Restart your IDE
```

The `InformixServiceGrpc` class will be generated and your errors will disappear! ✅