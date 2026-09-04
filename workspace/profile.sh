# /etc/profile resets PATH for a login shell, which is what every command runs in, so the
# directories a workspace installs its own tools into are added back here rather than in the
# environment the supervisor hands over — where they would simply be overwritten.
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) PATH="$HOME/.local/bin:$HOME/node_modules/.bin:$PATH" ;;
esac
export PATH
