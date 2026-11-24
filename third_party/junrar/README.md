# Junrar 8.1.2 source replacement

`ComprDataIO.java` is based on the source JAR for `com.github.junrar:junrar:8.1.2`, under the license in `../licenses/JUNRAR-LICENSE.txt`.

The patch keeps the original RAR5 AES-CBC cipher across volumes and feeds it complete blocks even when a part boundary falls inside one. The upstream final entry CRC/MAC verification remains in place. The Gradle script compiles this replacement against the pinned upstream binary and removes the original class from the packaged JAR. Recheck the replacement and the encrypted split fixture before updating Junrar.
