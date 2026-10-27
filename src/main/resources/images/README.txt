Drop these files here (exact names, PNG format):

  logo.png      - the app logo. Shown at the top of the sidebar, the bottom of
                  the sidebar (footer tile), and the hero mark on the About
                  page — every spot that currently shows a lettered/triangle
                  placeholder tile. A square image works best; it's scaled and
                  corner-clipped to fit each spot automatically.
  profile.png   - banner shown at the top of the Profile card on the Home page
  news1.png     - thumbnail for "Launcher 1.0 is out"
  news2.png     - thumbnail for "Website and store is out"
  news3.png     - thumbnail for "Veyro AI is here to help..."

If a file is missing, the app falls back to the existing styled placeholder
box automatically — it won't crash or show a broken image.

logo.png can also be dropped at the project root (next to build.gradle)
instead, which is picked up immediately without a rebuild — handy while
you're still iterating on the artwork. Once you're happy with it, move it
into this folder so it's bundled into the packaged app.
