package com.bt

import android.os.Parcel
import android.os.Parcelable

/**
 * Espelho EXATO do com.bt.BTDevice da central NWD (firmware K2401). A ordem dos campos no
 * writeToParcel/readFromParcel precisa bater byte a byte com o serviço, senão o binder desserializa errado.
 */
class BTDevice : Parcelable {
    var address: String? = null
    var name: String? = null
    var deviceClass: Int = 0
    var service: Int = 0
    var isPair: Boolean = false
    var pairRecordIndex: Int = 0
    var obdPassword: String? = null
    var isSinkDevice: Boolean = false
    var type: String? = null

    constructor()
    constructor(address: String?, name: String?) { this.address = address; this.name = name }

    private constructor(p: Parcel) {
        address = p.readString()
        name = p.readString()
        deviceClass = p.readInt()
        service = p.readInt()
        isPair = p.readInt() != 0
        pairRecordIndex = p.readInt()
        obdPassword = p.readString()
        isSinkDevice = p.readInt() != 0
        type = p.readString()
    }

    override fun writeToParcel(p: Parcel, flags: Int) {
        p.writeString(address)
        p.writeString(name)
        p.writeInt(deviceClass)
        p.writeInt(service)
        p.writeInt(if (isPair) 1 else 0)
        p.writeInt(pairRecordIndex)
        p.writeString(obdPassword)
        p.writeInt(if (isSinkDevice) 1 else 0)
        p.writeString(type)
    }

    override fun describeContents() = 0

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<BTDevice> {
            override fun createFromParcel(p: Parcel) = BTDevice(p)
            override fun newArray(size: Int) = arrayOfNulls<BTDevice>(size)
        }
    }
}
