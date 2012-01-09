#!/system/bin/sh
# Script to enable NAT/PAT (Masquerading) on eth0

echo "Stopping firewall and allowing everyone..."
iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X
iptables -P INPUT ACCEPT
iptables -P FORWARD ACCEPT
iptables -P OUTPUT ACCEPT

echo "Enabling SNAT (MASQUERADE) functionality on ppp0"
#iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
iptables -t nat -A POSTROUTING -o ppp0 -j MASQUERADE
