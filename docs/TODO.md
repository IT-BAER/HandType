# TODO

Open feature and release items. Remove a line when it lands; details go in `CHANGELOG.md`.

## Features

- Receive text via the Android share sheet: register an `ACTION_SEND` (`text/plain`) intent filter on
  `MainActivity`, read `Intent.EXTRA_TEXT` in `onCreate`/`onNewIntent`, open `RenderTemplate` with the
  text prefilled. Lets any app (notes, messenger, browser) send text straight into HandType.
