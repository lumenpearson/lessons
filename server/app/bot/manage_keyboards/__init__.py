"""Callback payloads and keyboards for the management pages, one module per
screen of «⚙️ Класс», named like the screens in ``handlers/manage/``, and
``_common`` for the class card's payload and the way back to it, which every
screen ends with.

Kept out of ``keyboards.py`` deliberately: that file is the vocabulary of the
day-to-day flows, and a prefix collision between the two would not fail a
build - it would silently route one feature's button into another feature's
handler. ``tests/test_bot_manage.py`` checks every prefix here against every
other payload in ``app.bot``, found by walking the package. A page's row cap is
its renderer's, in ``manage_render``: what is drawn and what can be pressed read
one number.
"""
