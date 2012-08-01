mkdir -p v2.3
mkdir -p v2.3/system
mkdir -p v2.3/data
mkdir -p v2.3/data/app
cd v2.3/system
cp /mnt_50g/cm7/out/target/product/D530/system/* . -R
cp /mnt_50g/cm7/out/target/product/D530/boot.img ..
cd ../..
ls v2.3/system/xbin -l | grep "\->"  > xbin.0627.list.txt
ls v2.3/system/bin -l | grep "\->"  > bin.0627.list.txt
cd v2.3/system/bin
ls  -l | grep "\->" | awk '{print $8}' | xargs rm -f
cd ../xbin
ls  -l | grep "\->" | awk '{print $8}' | xargs rm -f
cd ../../..
#cp /mnt_50g/a2.2/android-20120304-2.2.2_r1/out/target/product/generic/system/lib/libcameraservice.so .
#cp /mnt_50g/a2.2/android-20120304-2.2.2_r1/out/target/product/D530/obj/lib/libsystem_server.so .
#cp /mnt_50g/a2.2/android-20120304-2.2.2_r1/out/target/product/generic/system/lib/libandroid_servers.so .
