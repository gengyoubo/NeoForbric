package org.neoforbric.loader;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.neoforbric.forge.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

class ForgeModsTest {
    @TempDir Path root;
    private String toml(String id, String tail) { return "modLoader=\"javafml\"\nloaderVersion=\"[52,53)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""+id+"\"\nversion=\"${file.jarVersion}\"\n"+tail; }
    private Archive jar(String id, String tail) throws Exception {
        var manifest = new java.util.jar.Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Implementation-Version", "1.2.3");
        return Archive.read(TestJars.jar(root.resolve(id+".jar"), Map.of("META-INF/mods.toml", TestJars.text(toml(id,tail))), manifest));
    }
    @Test void forgeDiscoverySelectsForgeDescriptorEvenWhenFabricIsPresent() throws Exception {
        TestJars.jar(root.resolve("universal.jar"), Map.of("META-INF/mods.toml", TestJars.text(toml("universal", "")), "fabric.mod.json", TestJars.text("{\"schemaVersion\":1,\"id\":\"wrong\",\"version\":\"1\"}")));
        var result = ForgeDiscovery.discover(root,new AuditLog()); assertEquals("universal",result.mods().getFirst().metadata().id()); assertEquals(Metadata.Ecosystem.FORGE,result.mods().getFirst().metadata().ecosystem());
    }
    @Test void mandatoryOptionalSideAndOrderingAreEnforced() throws Exception {
        Archive aa = jar("aa", "[[dependencies.aa]]\nmodId=\"bb\"\nmandatory=true\nversionRange=\"[1.2,2)\"\nordering=\"AFTER\"\nside=\"BOTH\"\n"); Archive bb = jar("bb", "");
        var a = new Discovery.Candidate(aa,ForgeMetadata.read(aa).mods().getFirst()); var b = new Discovery.Candidate(bb,ForgeMetadata.read(bb).mods().getFirst());
        assertEquals("1.2.3",a.metadata().version()); assertEquals(List.of("bb","aa"),Resolver.plan(List.of(a,b),"server",new AuditLog(),false,false,true).mods().stream().map(m->m.metadata().id()).toList());
        assertEquals("MISSING_DEPENDENCY",assertThrows(Failure.class,()->Resolver.plan(List.of(a),"server",new AuditLog(),false,false,true)).code());
        assertEquals("NATIVE_RUNTIME_UNSUPPORTED",assertThrows(Failure.class,()->Resolver.plan(List.of(b),"server",new AuditLog(),false,false)).code());
    }
    @Test void unadaptedTakeoverIsRejectedBeforeConstruction() throws Exception {
        Archive archive = Archive.read(TestJars.jar(root.resolve("service.jar"),Map.of("META-INF/mods.toml",TestJars.text(toml("service","")),"META-INF/services/cpw.mods.modlauncher.api.ITransformationService",TestJars.text("danger.Service"))));
        var mod=new Discovery.Candidate(archive,ForgeMetadata.read(archive).mods().getFirst()); assertEquals("FORGE_FEATURE_UNSUPPORTED",assertThrows(Failure.class,()->ForgeAdmission.admit(mod)).code());
        var library = Archive.read(TestJars.jar(root.resolve("library.jar"), Map.of("META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService", TestJars.text("danger.Plugin"))));
        assertEquals("FORGE_FEATURE_UNSUPPORTED", assertThrows(Failure.class, () -> ForgeAdmission.library(library)).code());
    }
    @Test void optionalDependenciesAndDistDoNotBecomeMandatory() throws Exception {
        var archive = jar("optional", "[[dependencies.optional]]\nmodId=\"bb\"\nmandatory=false\nversionRange=\"[2,3)\"\nside=\"BOTH\"\n");
        var optional = new Discovery.Candidate(archive, ForgeMetadata.read(archive).mods().getFirst());
        assertEquals(List.of(optional), Resolver.plan(List.of(optional), "server", new AuditLog(), false, false, true).mods());
        var installed = jar("bb", ""); var bb = new Discovery.Candidate(installed, ForgeMetadata.read(installed).mods().getFirst());
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class, () -> Resolver.plan(List.of(optional, bb), "server", new AuditLog(), false, false, true)).code());
        var sided = jar("sided", "[[dependencies.sided]]\nmodId=\"missing\"\nmandatory=true\nversionRange=\"[1,2)\"\nside=\"CLIENT\"\n");
        var mod = new Discovery.Candidate(sided, ForgeMetadata.read(sided).mods().getFirst());
        assertEquals(List.of(mod), Resolver.plan(List.of(mod), "server", new AuditLog(), false, false, true).mods());
        assertEquals("MISSING_DEPENDENCY", assertThrows(Failure.class, () -> Resolver.plan(List.of(mod), "client", new AuditLog(), false, false, true)).code());
    }
    @Test void mapsSrgBytecodeAndRefmapButRejectsUnknownMembers() throws Exception {
        ClassWriter writer=new ClassWriter(0);writer.visit(Opcodes.V21,Opcodes.ACC_PUBLIC,"test/Mapping",null,"java/lang/Object",null);
        var method=writer.visitMethod(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC,"value","(Ltest/Game;)I",null,null);method.visitCode();method.visitVarInsn(Opcodes.ALOAD,0);method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,"test/Game","m_123_","()I",false);method.visitInsn(Opcodes.IRETURN);method.visitMaxs(1,1);method.visitEnd();writer.visitEnd();
        Archive input=Archive.read(TestJars.jar(root.resolve("mapping.jar"),Map.of("test/Mapping.class",writer.toByteArray(),"test.refmap.json",TestJars.text("{\"selector\":\"Ltest/Game;m_123_()I\"}"))));
        var mapped=ForgeRemapper.remap(input,root.resolve("remapped"),Map.of("m_123_","value"),new AuditLog());ClassNode node=new ClassNode();new ClassReader(mapped.read("test/Mapping.class")).accept(node,0);
        assertEquals("value",Arrays.stream(node.methods.getFirst().instructions.toArray()).filter(i->i instanceof MethodInsnNode).map(i->((MethodInsnNode)i).name).findFirst().orElseThrow());assertTrue(new String(mapped.read("test.refmap.json")).contains("value()I"));
        assertEquals("FORGE_MAPPING",assertThrows(Failure.class,()->ForgeRemapper.remap(input,root.resolve("bad"),Map.of(),new AuditLog())).code());
    }
    @Test void referenceJeiRangeUsesNativeMatrixAndAuditsActualVersion() throws Exception {
        Archive archive=jar("jei", "[[dependencies.jei]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.21,1.21.1)\"\nordering=\"NONE\"\nside=\"BOTH\"\n");
        var mod=new Discovery.Candidate(archive,ForgeMetadata.read(archive).mods().getFirst()); var audit = new AuditLog();
        assertEquals(List.of(mod), Resolver.plan(List.of(mod),"client",audit,false,false,true).mods());
        var accepted = audit.events().stream().filter(e -> e.type().equals("DEPENDENCY_VERSION_COMPAT")).findFirst().orElseThrow();
        assertEquals("1.21.1", accepted.details().get("selected")); assertEquals("1.21", accepted.details().get("compatibleVersion"));
    }
    @Test void matrixIsScopedByMinecraftTypeAndIdAndKeepsUnsupportedRangesRejected() throws Exception {
        assertEquals(Optional.of("51.0.33"), ForgeVersionSupport.fallback("1.21.1", "mod", "forge", "[51,52)"));
        assertEquals(Optional.of("51"), ForgeVersionSupport.fallback("1.21.1", "languageloader", "javafml", "[51,52)"));
        for (String[] query : List.of(new String[]{"1.21.2", "mod", "minecraft", "[1.21,1.21.1)"}, new String[]{"1.21.1", "mod", "javafml", "[51,52)"}, new String[]{"1.21.1", "languageloader", "lowcodefml", "[51,52)"}, new String[]{"1.21.1", "mod", "minecraft", "[1.20,1.21)"}))
            assertTrue(ForgeVersionSupport.fallback(query[0], query[1], query[2], query[3]).isEmpty());
        var archive=jar("unsupported", "[[dependencies.unsupported]]\nmodId=\"minecraft\"\nmandatory=true\nversionRange=\"[1.22,)\"\n");
        var mod=new Discovery.Candidate(archive,ForgeMetadata.read(archive).mods().getFirst());
        assertEquals("DEPENDENCY_VERSION", assertThrows(Failure.class,()->Resolver.plan(List.of(mod),"server",new AuditLog(),false,false,true)).code());
        assertFalse(ForgeVersionSupport.modMatches("1.21.1", "owner", "missing", "[1,2)", null, new AuditLog()));
        assertTrue(ForgeVersionSupport.modMatches("1.21.1", "owner", "other", "[8,9)", "0.0NONE", new AuditLog()));
    }
}
