# Rows the program doesn't redraw survive the app going to the background:
# Android frees the window's drawings there once memory has run low, and the
# terminal has to draw every row again when it's back, not only the ones that
# change. With the keyboard down, so coming back doesn't resize the terminal
# (which draws every row anyway).
no_keyboard
send "zt bands"
sleep 2
shot front
# Low on memory, then away and back again: that second time, the window's
# drawings go.
to_back
sleep 2
trim_memory
to_front
sleep 2
to_back
sleep 2
to_front
sleep 2
shot back
pixels bands-whole "$out/$scenario-front.png" "$out/$scenario-back.png"
