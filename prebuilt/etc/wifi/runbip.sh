#!/system/xbin/sh 
echo "enter scripts..."
WRITE_MAC_RESULT=yulong.write_mac.result
check_ret()
{
  if [ "$1"  -ne 0 ]
  then
	setprop  $WRITE_MAC_RESULT $1
	exit 1
  fi
}

wifi_mac_prop=`getprop persist.sys.wifi_mac`
if [ -z "$wifi_mac_prop" ]
then
	echo "=======$wifi_mac_prop========"
	echo "wifi mac not set yet"
	wifi_mac_prop=00:16:6d:01:23:45
	echo "set default mac address: $wifi_mac_prop"
fi
#setprop xiaojsj $wifi_mac_prop
setprop xiaojsj1 1111

echo "runbip.sh V.1.0 "
echo "generate wifi mac address"

echo "=======$wifi_mac_prop========"
setprop xiaojsj2 222
if ! insmod /system/etc/wifi/tiwlan_drv.ko
then
    setprop xiaojsj3 2323232
    echo "ERROR - cannot load tiwlan_drv.ko module!!!"
    echo "Exiting"
    setprop  $WRITE_MAC_RESULT 1
    exit 1
fi
setprop xiaojsj4 333
echo "Loading WiFi test firmware"

if ! tiwlan_loader_org -f /system/etc/wifi/firmware.bin -i /system/etc/wifi/tiwlan.ini
then
    echo "ERROR - cannot load wifi test firmware!!!"
    echo "Exiting"
    setprop  $WRITE_MAC_RESULT 1
    exit 1
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

echo "begin to close wifi interface and unload wifi drvier..."
ifconfig tiwlan0 down
check_ret $?
rmmod tiwlan_drv.ko
check_ret $?
write_mac_result=`getprop yulong.write_mac.result`
setprop xiaojsj5 444
echo "===========finish==========result:$write_mac_result===================="
