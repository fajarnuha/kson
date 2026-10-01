## Runtime dependencies

Jars resolved for each library's runtime classpath. `kotlin-stdlib` and `org.jetbrains:annotations` are
listed but left out of the total, because every Kotlin app already has them.

| Library | Jars (excluding stdlib) | Total size |
|---|---|---:|
| kson | `project :kson:kson` (280.1 KB) | 280.1 KB |
| kotlinx.serialization | `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.11.0` (287.3 KB)<br>`org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.11.0` (395.0 KB) | 682.3 KB |
| moshi | `com.squareup.moshi:moshi:1.15.2` (158.5 KB)<br>`com.squareup.okio:okio-jvm:3.7.0` (352.2 KB) | 510.6 KB |
| gson | `com.google.code.gson:gson:2.14.0` (306.3 KB)<br>`com.google.errorprone:error_prone_annotations:2.48.0` (19.8 KB) | 326.0 KB |
| jackson (+kotlin module) | `com.fasterxml.jackson.core:jackson-databind:2.22.3` (1,669.5 KB)<br>`com.fasterxml.jackson.core:jackson-core:2.22.3` (580.8 KB)<br>`com.fasterxml.jackson.module:jackson-module-kotlin:2.22.3` (248.3 KB)<br>`com.fasterxml.jackson.core:jackson-annotations:2.22` (82.2 KB)<br>`org.jetbrains.kotlin:kotlin-reflect:2.1.21` (3,009.1 KB) | 5,590.0 KB |

## Model bytecode

Compiled classes for the same benchmark model (`User`, `UserPage`, `Address`, `Geo`, `Friend`, `Role`),
including everything the library's code generator emitted for it.

| Library | Classes | Bytes | Generated classes |
|---|---:|---:|---|
| gson | 6 | 22,280 | 0 |
| jackson | 6 | 22,493 | 0 |
| kotlinx | 17 | 75,251 | 5 |
| kson | 20 | 88,762 | 14 |
| moshi | 11 | 56,481 | 5 |
