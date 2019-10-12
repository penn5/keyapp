package tk.hack5.keyapp

import android.bluetooth.le.ScanResult
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.view.get

class ScanAdapter(context: Context) :
    ArrayAdapter<ScanResult>(context, android.R.layout.simple_list_item_2) {
    private val inflater = LayoutInflater.from(context)!!
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (convertView as ViewGroup?) ?: inflater.inflate(
            R.layout.triple_list_layout,
            parent,
            false
        ) as ViewGroup
        val item = getItem(position)
        (view[0] as TextView).text = item!!.device!!.name
        (view[1] as TextView).text = item.device!!.address
        (view[2] as TextView).text = item.rssi.toString()
        return view
    }
}
