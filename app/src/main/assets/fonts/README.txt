Caption fonts — asal .ttf files yahan is naam se rakhein:

  JameelNooriNastaleeq.ttf      <- Jameel Noori Nastaleeq (Urdu Nastaliq)
  NotoNastaliqUrdu-Regular.ttf  <- Noto Nastaliq Urdu (Google Fonts, free — https://fonts.google.com/noto/specimen/Noto+Nastaliq+Urdu)
  Roboto-Regular.ttf            <- English
  Poppins-Regular.ttf           <- English (Google Fonts, free — https://fonts.google.com/specimen/Poppins)

Jab tak asal file yahan na ho, us font ko select karne par app khud-ba-khud
system ke default font par wapas chali jati hai — koi crash nahi hota, bas
custom font nazar nahi aata.

File ka naam BILKUL upar jaisa hi hona chahiye (case-sensitive), warna
app usay nahi dhoond payegi. Naya font add karna ho to:
com.vellora.cut.autogen.data.CaptionFonts (AutoGenEntities.kt) mein
ek nayi entry add karein.
