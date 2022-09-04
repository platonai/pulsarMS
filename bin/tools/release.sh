#bin

bin=$(dirname "$0")/..
bin=$(cd "$bin">/dev/null || exit; pwd)
APP_HOME=$(cd "$bin"/..>/dev/null || exit; pwd)

# Switching remote URLs from HTTPS to SSH
git remote set-url origin git@github.com:platonai/pulsar.git

SNAPSHOT_VERSION=$(head -n 1 "$APP_HOME/VERSION")
VERSION=${SNAPSHOT_VERSION//"-SNAPSHOT"/""}
LAST_COMMIT_ID=$(git log --format="%H" -n 1)
BRANCH=$(git branch --show-current)
TAG="v$VERSION"

echo "Ready to checkout HEAD"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git checkout HEAD
else
  echo "Bye."
  exit 0
fi

echo "Ready to add tag $TAG on $LAST_COMMIT_ID"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git tag "$TAG" "$LAST_COMMIT_ID"
else
  echo "Bye."
  exit 0
fi

echo "Ready to push with tags to $BRANCH"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git push --tags
else
  echo "Bye."
  exit 0
fi

echo "Ready to merge to main branch"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git checkout main

  # The main branch name is master
  exitCode=$?
  [ ! $exitCode -eq 0 ] && git checkout master

  git merge "$BRANCH"
else
  echo "Bye."
  exit 0
fi

echo "Ready to push to main branch"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git push
else
  echo "Bye."
  exit 0
fi

echo "Ready to checkout $BRANCH"
read -p "Are you sure to continue? [Y/n]" -n 1 -r
echo    # (optional) move to a new line
if [[ $REPLY =~ ^[Yy]$ ]]; then
  git checkout "$BRANCH"
else
  echo "Bye."
  exit 0
fi