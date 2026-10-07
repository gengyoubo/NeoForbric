var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var IntInsnNode = Java.type('org.objectweb.asm.tree.IntInsnNode');
function initializeCoreMod() {
    return {
        'probe_value': {
            'target': {'type': 'CLASS', 'name': 'demo.forgeprobe.CoremodTarget'},
            'transformer': function(clazz) {
                for (var i = 0; i < clazz.methods.size(); i++) {
                    var method = clazz.methods.get(i);
                    if (!method.name.equals('value') || !method.desc.equals('()I')) continue;
                    for (var j = 0; j < method.instructions.size(); j++) {
                        var instruction = method.instructions.get(j);
                        if (instruction.getOpcode() === Opcodes.ICONST_1)
                            method.instructions.set(instruction, new IntInsnNode(Opcodes.BIPUSH, 7));
                    }
                }
                return clazz;
            }
        }
    };
}
