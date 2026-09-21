# Third-party components

The Kotlin Hub no longer distributes Qt or MinGW. Its desktop package contains:

- Kotlin, kotlinx.coroutines and kotlinx.serialization: Apache-2.0.
- Compose Multiplatform, AndroidX and Skiko: upstream Apache-2.0 notices.
- Skia and its bundled dependencies: upstream BSD and third-party notices.
- Apache Commons Compress, IO, Codec and Lang: Apache-2.0.
- A bundled OpenJDK runtime: GPL-2.0 with the Classpath Exception and its own notices.

Dependency JARs retain their META-INF license and notice files. The packaged
runtime includes its legal directory. Additional component notices are collected
under licenses/dependencies during packaging, together with the resolved artifact
inventory. Consult those files for exact versions and copyright holders.

The historical licenses/Qt directory relates only to the archived Hub 0.2 build;
it is not part of the Kotlin distribution.