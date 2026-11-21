package org.example.kmphr.di

import org.example.core.CoreModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module

interface Greeter { fun greet(): String }

@Factory(binds = [Greeter::class])
class GreeterImpl : Greeter {
    override fun greet(): String = "g1"
}

@Module
@ComponentScan("org.example.kmphr.di")
class AppModule

@KoinApplication(modules = [AppModule::class, CoreModule::class])
object KoinApp
