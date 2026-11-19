plugins {
    id("com.solufee.cmp.hotreload")
}

// No modules declared: the plugin discovers them. :app has main.kt; :core and :ui are libraries
// in path order, so :ui links against :core.
cmpHotReload {
    simulator {
        readyMarker = "RENDER_APP"
    }
}
