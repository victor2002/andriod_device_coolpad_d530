#!/system/xbin/sh 
echo "enter scripts..."
#su
if [ -f /data/misc/wifi/mac ]
then
echo "file mac exist"
exit 1
fi

#setprop  persist.sys.wifi_mac 00:16:6d:22:23:45
#wifi_mac_prop=`getprop persist.sys.wifi_mac`

echo parameter=$1

if [ -z $1 ]
then
wifi_mac_prop=`getprop persist.sys.wifi_mac`
else
wifi_mac_prop=$1
fi

if [ -z "$wifi_mac_prop" ]
then
	echo "====set default mac address======"
	echo "wifi mac not set yet"
	wifi_mac_prop=00:16:6d:01:23:45
	echo "set default mac address: $wifi_mac_prop"
fi

ifconfig tiwlan0 up
check_ret $?
echo "Waiting Interface to be up"
sleep 2
echo "wifi RF calibrate begin..."
echo / w p 1 > /data/misc/wifi/bip.cmd
echo / w f 2 >> /data/misc/wifi/bip.cmd
echo / w l 2 >> /data/misc/wifi/bip.cmd
echo / t r h 0 7 >> /data/misc/wifi/bip.cmd
echo / t b b 375 128 0 >> /data/misc/wifi/bip.cmd
echo / t b t 1 0 0 0 0 0 0 0 >> /data/misc/wifi/bip.cmd
echo / q >> /data/misc/wifi/bip.cmd

#note: nvs_map.bin generated in /data/misc/wifi/ position
if ! wlan_cu -b < /data/misc/wifi/bip.cmd > /dev/null 2>&1
then
    echo "ERROR - cannot WIFI RF calibrate!!!"
    echo "Exiting"
    setprop  $WRITE_MAC_RESULT 1
    exit 1
fi
	
rm /data/misc/wifi/bip.cmd
echo "wifi RF calibrate finished!!!"

if ls /data/misc/wifi/nvs_map.bin > /dev/null 2>&1 
then
	echo "begin to write mac address"
	write_mac $wifi_mac_prop /dev/yl_params
	check_ret $?
#	write_mac $wifi_mac_prop /nvs_map.bin
#	rm /data/misc/wifi/nvs_map.bin
	setprop  $WRITE_MAC_RESULT 0
    	echo "PASS"		
else
    echo "ERROR - cannot run bip!!!"
    setprop  $WRITE_MAC_RESULT 1		
fi

if [ -f /data/misc/wifi/mac ]
then
echo "file mac exist"
else
echo "file fix_mac generate fail"
touch /data/misc/wifi/mac
fi


if [ -f /data/misc/wifi/mac ]
then
echo "file mac exist"
else
echo "file fix_mac generate fail2"
echo haha > /data/misc/wifi/mac
fi

if [ -f /data/misc/wifi/mac ]
then
echo "file fix_mac exist"
else
echo "file fix_mac generate fail3!!!!"
fi


echo "===========finish==========result:$write_mac_result===================="
