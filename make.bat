@if not exist org mkdir org
@cd src
::@dir /s /b *.java > ../org/srcfiles.txt
@cd ..
javac -d . -classpath sqlitejdbc-v056.jar @org/srcfiles.txt

@pause
