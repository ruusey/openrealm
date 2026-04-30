start java -jar ../openrealm-data/target/openrealm-data.jar
timeout 8 > NUL
start java -jar ./target/openrealm-shaded.jar 127.0.0.1
pause
