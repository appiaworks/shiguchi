/**
 * The one incompatibility japicmp will not flag for us.
 *
 * japicmp judges compatibility from a caller's point of view, where adding a method to an
 * interface is harmless — its report says so, "added in source compatible way". For an SPI the
 * judgement is backwards: these interfaces exist to be *implemented* by applications, so a new
 * abstract method breaks every implementor's build. japicmp has no setting to promote that to a
 * failure, so it is compared here instead.
 *
 * Both jars are read with a throwaway class loader, which is enough to ask Java itself which
 * methods are abstract rather than re-implementing the class-file format. Types that fail to
 * load (a supertype from a dependency that is not on this classpath) are skipped: unreadable is
 * not the same as broken, and a false failure here would be worse than a missed one.
 */
object StableSpi {

    private const val MARKER = "dev.appiaworks.protobuf.extras.api.ApiStatus"

    fun addedAbstractMethods(baselineJar: java.io.File, currentJar: java.io.File, classpath: Set<java.io.File>): List<String> {
        val before = stableInterfaceMethods(baselineJar, classpath)
        val after = stableInterfaceMethods(currentJar, classpath)
        // Only types that were already stable count: a newly published SPI cannot break anyone.
        return after.filterKeys { before.containsKey(it) }
            .flatMap { (type, methods) -> (methods - before.getValue(type)).map { "$type.$it" } }
            .sorted()
    }

    private fun stableInterfaceMethods(jar: java.io.File, classpath: Set<java.io.File>): Map<String, Set<String>> {
        val urls = (listOf(jar) + classpath).map { it.toURI().toURL() }.toTypedArray()
        val result = mutableMapOf<String, Set<String>>()
        java.net.URLClassLoader(urls, StableSpi::class.java.classLoader).use { loader ->
            java.util.zip.ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.endsWith(".class") && !it.contains('$') }
                    .map { it.removeSuffix(".class").replace('/', '.') }
                    .forEach { className ->
                        val type = runCatching { loader.loadClass(className) }.getOrNull() ?: return@forEach
                        if (!type.isInterface || !isStable(type)) return@forEach
                        val abstractMethods = runCatching {
                            type.declaredMethods
                                .filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
                                .map { m -> m.name + m.parameterTypes.joinToString(",", "(", ")") { it.name } }
                                .toSet()
                        }.getOrNull() ?: return@forEach
                        result[className] = abstractMethods
                    }
            }
        }
        return result
    }

    /** True when the type carries @ApiStatus(STABLE); the marker has CLASS retention, so this reads it as raw data. */
    private fun isStable(type: Class<*>): Boolean {
        val annotation = type.annotations.firstOrNull { it.annotationClass.qualifiedName == MARKER }
            ?: return false
        val value = runCatching {
            annotation.annotationClass.java.getMethod("value").invoke(annotation)
        }.getOrNull() ?: return false
        return (value as? Enum<*>)?.name == "STABLE"
    }
}
